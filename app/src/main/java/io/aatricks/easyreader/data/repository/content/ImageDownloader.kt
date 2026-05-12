package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.util.HttpRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImageFetchResult {
    data class Success(val file: File) : ImageFetchResult
    data class BoundedSuccess(val bytes: ByteArray) : ImageFetchResult
    data class HttpError(val code: Int, val retryAfterMs: Long? = null) : ImageFetchResult
    data class NetworkError(val exception: IOException) : ImageFetchResult
    object TooLarge : ImageFetchResult

    fun isRetryable(): Boolean = when (this) {
        is Success, is BoundedSuccess, is TooLarge -> false
        is NetworkError -> true
        is HttpError -> HttpRetry.shouldRetryResponseCode(code)
    }
}

@Singleton
class ImageDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val NON_ESSENTIAL_TIMEOUT_SECONDS = 5L
        private const val USER_TIMEOUT_SECONDS = 15L
        private const val HOST_SUCCESS_SPACING_MS = 25L
        private const val HOST_RATE_LIMIT_SPACING_MS = 1200L
        private const val HOST_NETWORK_ERROR_SPACING_MS = 300L
        private const val PER_HOST_CONCURRENCY = 8
        private const val MAX_HOST_THROTTLE_STATES = 256
        const val MAX_HOST_THROTTLE_MS = 10_000L
        private const val USER_REQUEST_ATTEMPTS = 3
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024L // 20MB
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
    }

    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val userTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(USER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(USER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(USER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val hostThrottleMutex = Mutex()
    private val hostThrottleStates = object : LinkedHashMap<String, HostThrottleState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HostThrottleState>?): Boolean {
            return size > MAX_HOST_THROTTLE_STATES
        }
    }

    private data class HostThrottleState(
        val semaphore: Semaphore = Semaphore(PER_HOST_CONCURRENCY),
        val throttleMutex: Mutex = Mutex(),
        var nextAllowedAtMs: Long = 0L
    )

    suspend fun executeImageRequest(
        imageUrl: String,
        pageUrl: String,
        priority: ImageRequestPriority,
        rangeHeader: String? = null,
        destinationFile: File? = null
    ): ImageFetchResult {
        val useShortTimeout = priority == ImageRequestPriority.SPECULATIVE
        val attempts = if (useShortTimeout) SHORT_REQUEST_ATTEMPTS else USER_REQUEST_ATTEMPTS
        val client = if (useShortTimeout) shortTimeoutClient else userTimeoutClient

        repeat(attempts) { attempt ->
            when (
                val result = runCatching {
                    executeHostThrottled(imageUrl) {
                        val requestBuilder = Request.Builder()
                            .url(imageUrl)
                            .addHeader("User-Agent", "Mozilla/5.0")
                            .addHeader("Referer", getReferer(pageUrl))
                        if (rangeHeader != null) {
                            requestBuilder.addHeader("Range", rangeHeader)
                        }

                        client.newCall(requestBuilder.build()).execute().use { response ->
                            parseImageResponse(response, destinationFile)
                        }
                    }
                }.getOrElse { throwable ->
                    ImageFetchResult.NetworkError(throwable as? IOException ?: IOException(throwable))
                }
            ) {
                is ImageFetchResult.Success -> return result
                is ImageFetchResult.BoundedSuccess -> return result
                is ImageFetchResult.TooLarge -> return result
                is ImageFetchResult.HttpError -> {
                    if (!HttpRetry.shouldRetryResponseCode(result.code) || attempt == attempts - 1) return result
                    delay(HttpRetry.nextRetryDelayMs(result.retryAfterMs, imageUrl, attempt))
                }

                is ImageFetchResult.NetworkError -> {
                    if (attempt == attempts - 1) return result
                    delay(HttpRetry.nextRetryDelayMs(null, imageUrl, attempt))
                }
            }
        }

        return ImageFetchResult.HttpError(code = 0)
    }

    private suspend fun executeHostThrottled(
        imageUrl: String,
        block: suspend () -> ImageFetchResult
    ): ImageFetchResult {
        val state = hostThrottleStateFor(imageUrl)
        return state.semaphore.withPermit {
            waitForHostThrottle(state)
            val result = block()
            recordHostResult(state, result)
            result
        }
    }

    private suspend fun waitForHostThrottle(state: HostThrottleState) {
        state.throttleMutex.withLock {
            val waitMs = (state.nextAllowedAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (waitMs > 0) {
                delay(waitMs)
            }
        }
    }

    private suspend fun recordHostResult(state: HostThrottleState, result: ImageFetchResult) {
        val spacingMs = when (result) {
            is ImageFetchResult.HttpError -> {
                val retryAfter = result.retryAfterMs
                when {
                    result.code == 429 && retryAfter != null -> retryAfter
                    HttpRetry.shouldRetryResponseCode(result.code) -> HOST_RATE_LIMIT_SPACING_MS
                    else -> HOST_SUCCESS_SPACING_MS
                }
            }
            is ImageFetchResult.NetworkError -> HOST_NETWORK_ERROR_SPACING_MS
            is ImageFetchResult.Success,
            is ImageFetchResult.BoundedSuccess,
            is ImageFetchResult.TooLarge -> HOST_SUCCESS_SPACING_MS
        }

        state.throttleMutex.withLock {
            val nextAllowed = System.currentTimeMillis() + spacingMs
            if (nextAllowed > state.nextAllowedAtMs) {
                state.nextAllowedAtMs = nextAllowed
            }
        }
    }

    private suspend fun hostThrottleStateFor(imageUrl: String): HostThrottleState {
        val hostKey = imageUrl.toHttpUrlOrNull()?.host?.lowercase() ?: imageUrl
        return hostThrottleMutex.withLock {
            hostThrottleStates.getOrPut(hostKey) { HostThrottleState() }
        }
    }

    private fun parseImageResponse(
        response: Response,
        destinationFile: File? = null
    ): ImageFetchResult {
        if (!response.isSuccessful) {
            return ImageFetchResult.HttpError(
                code = response.code,
                retryAfterMs = HttpRetry.parseRetryAfterMs(response.header("Retry-After"), MAX_HOST_THROTTLE_MS)
            )
        }

        val body = response.body ?: return ImageFetchResult.HttpError(response.code)
        val maxBytes = if (destinationFile != null) MAX_IMAGE_BYTES else MAX_DIMENSION_SNIFF_BYTES

        val contentLength = body.contentLength()
        if (contentLength != -1L && contentLength > maxBytes) {
            return ImageFetchResult.TooLarge
        }

        return if (destinationFile != null) {
            try {
                destinationFile.sink().buffer().use { sink ->
                    val source = body.source()
                    var totalRead = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 8192)
                        if (read == -1L) break
                        totalRead += read
                        if (totalRead > maxBytes) {
                            return ImageFetchResult.TooLarge
                        }
                        sink.emitCompleteSegments()
                    }
                }
                ImageFetchResult.Success(destinationFile)
            } catch (e: Exception) {
                ImageFetchResult.NetworkError(e as? IOException ?: IOException(e))
            }
        } else {
            try {
                body.source().use { source ->
                    val buffer = okio.Buffer()
                    val sniffLimit = maxBytes + 1
                    var totalRead = 0L
                    while (totalRead < sniffLimit) {
                        val read = source.read(buffer, sniffLimit - totalRead)
                        if (read == -1L) break
                        totalRead += read
                    }
                    if (totalRead > maxBytes) {
                        ImageFetchResult.TooLarge
                    } else {
                        ImageFetchResult.BoundedSuccess(buffer.readByteArray())
                    }
                }
            } catch (e: Exception) {
                ImageFetchResult.NetworkError(e as? IOException ?: IOException(e))
            }
        }
    }

    fun getReferer(url: String): String = try {
        if (url.contains("mangabat") || url.contains("manganato")) {
            "https://manganato.com/"
        } else {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        }
    } catch (e: Exception) {
        url
    }
}
