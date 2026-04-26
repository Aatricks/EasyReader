package io.aatricks.novelscraper.data.repository.content

import io.aatricks.novelscraper.data.model.ImageRequestPriority
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

sealed interface ImageFetchResult {
    data class Success(val file: File) : ImageFetchResult
    data class BoundedSuccess(val bytes: ByteArray) : ImageFetchResult
    data class HttpError(val code: Int, val retryAfterMs: Long? = null) : ImageFetchResult
    data class NetworkError(val exception: IOException) : ImageFetchResult
    object TooLarge : ImageFetchResult

    fun isRetryable(): Boolean = when (this) {
        is Success, is BoundedSuccess, is TooLarge -> false
        is NetworkError -> true
        is HttpError -> code == 408 || code == 429 || code in listOf(500, 502, 503, 504)
    }
}

@Singleton
class ImageDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val NON_ESSENTIAL_TIMEOUT_SECONDS = 5L
        private const val HOST_REQUEST_SPACING_MS = 450L
        private const val HOST_RATE_LIMIT_SPACING_MS = 1200L
        private const val USER_REQUEST_ATTEMPTS = 4
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024L // 20MB
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
    }

    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val hostThrottleMutex = Mutex()
    private val hostThrottleStates = mutableMapOf<String, HostThrottleState>()

    private data class HostThrottleState(
        val mutex: Mutex = Mutex(),
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
        val client = if (useShortTimeout) shortTimeoutClient else okHttpClient

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
                    if (!shouldRetryResponseCode(result.code) || attempt == attempts - 1) return result
                    delay(nextRetryDelayMs(result.retryAfterMs, imageUrl, attempt))
                }

                is ImageFetchResult.NetworkError -> {
                    if (attempt == attempts - 1) return result
                    delay(nextRetryDelayMs(null, imageUrl, attempt))
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
        return state.mutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (state.nextAllowedAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0) {
                delay(waitMs)
            }

            val result = block()
            val spacingMs = when (result) {
                is ImageFetchResult.HttpError -> {
                    val retryAfter = result.retryAfterMs
                    if (result.code == 429 && retryAfter != null) {
                        retryAfter
                    } else if (shouldRetryResponseCode(result.code)) {
                        HOST_RATE_LIMIT_SPACING_MS
                    } else {
                        HOST_REQUEST_SPACING_MS
                    }
                }

                is ImageFetchResult.NetworkError -> HOST_RATE_LIMIT_SPACING_MS
                is ImageFetchResult.Success -> HOST_REQUEST_SPACING_MS
                is ImageFetchResult.BoundedSuccess -> HOST_REQUEST_SPACING_MS
                is ImageFetchResult.TooLarge -> HOST_REQUEST_SPACING_MS
            }
            state.nextAllowedAtMs = System.currentTimeMillis() + spacingMs
            result
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
                retryAfterMs = response.header("Retry-After")?.let(::parseRetryAfterMs)
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
                    val read = source.read(buffer, maxBytes + 1)
                    if (read > maxBytes) {
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

    private fun parseRetryAfterMs(value: String): Long? {
        val seconds = value.trim().toLongOrNull() ?: return null
        return seconds.coerceAtLeast(1L) * 1000L
    }

    private fun shouldRetryResponseCode(code: Int): Boolean {
        return code == 408 || code == 429 || code in listOf(500, 502, 503, 504)
    }

    private fun nextRetryDelayMs(
        retryAfterMs: Long?,
        key: String,
        attempt: Int
    ): Long {
        if (retryAfterMs != null) return retryAfterMs
        val baseDelay = 400L * (1L shl attempt.coerceAtMost(3))
        val jitter = (key.hashCode().toLong().absoluteValue % 180L)
        return baseDelay + jitter
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
