package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.util.HttpRetry
import io.aatricks.easyreader.util.HttpTimeouts
import kotlinx.coroutines.delay
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
    private val okHttpClient: OkHttpClient,
    private val hostThrottle: HostThrottle = HostThrottle()
) {
    companion object {
        const val MAX_HOST_THROTTLE_MS = HostThrottle.MAX_RETRY_AFTER_MS
        private const val USER_REQUEST_ATTEMPTS = 3
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024L // 20MB
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
        private const val MANGABAT_REFERER = "https://www.mangabats.com/"
        private const val MANGANATO_REFERER = "https://manganato.com/"
        const val SUPPORTED_IMAGE_ACCEPT_HEADER = "image/webp,image/jpeg,image/png,image/gif,image/*;q=0.8,*/*;q=0.5"
    }

    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val userTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
        .build()

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
                    hostThrottle.execute(
                        url = imageUrl,
                        block = {
                            val requestBuilder = Request.Builder()
                                .url(imageUrl)
                                .addHeader("User-Agent", "Mozilla/5.0")
                                .addHeader("Accept", SUPPORTED_IMAGE_ACCEPT_HEADER)
                                .addHeader("Referer", getReferer(pageUrl))
                            if (rangeHeader != null) {
                                requestBuilder.addHeader("Range", rangeHeader)
                            }

                            client.newCall(requestBuilder.build()).execute().use { response ->
                                parseImageResponse(response, destinationFile)
                            }
                        },
                        classify = ::classifyImageOutcome
                    )
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

    private fun classifyImageOutcome(result: ImageFetchResult): HostThrottle.Outcome = when (result) {
        is ImageFetchResult.HttpError -> when {
            result.code == 429 -> HostThrottle.Outcome.RateLimited(result.retryAfterMs)
            HttpRetry.shouldRetryResponseCode(result.code) -> HostThrottle.Outcome.RetryableError
            else -> HostThrottle.Outcome.Success
        }
        is ImageFetchResult.NetworkError -> HostThrottle.Outcome.NetworkError
        is ImageFetchResult.Success,
        is ImageFetchResult.BoundedSuccess,
        is ImageFetchResult.TooLarge -> HostThrottle.Outcome.Success
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

        val body = response.body
        val maxBytes = if (destinationFile != null) MAX_IMAGE_BYTES else MAX_DIMENSION_SNIFF_BYTES

        val contentLength = body.contentLength()
        if (contentLength != -1L && contentLength > maxBytes) {
            return ImageFetchResult.TooLarge
        }

        return if (destinationFile != null) {
            try {
                var totalRead = 0L
                destinationFile.sink().buffer().use { sink ->
                    val source = body.source()
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
                // Some servers close the connection mid-stream without throwing — OkHttp
                // returns -1 cleanly and we end up persisting a truncated body. If the
                // response advertised a Content-Length, fail loud when the bytes we got
                // don't match so the retry path runs and the disk file never enters the
                // cache in a half-baked state.
                if (contentLength != -1L && totalRead != contentLength) {
                    return ImageFetchResult.NetworkError(
                        IOException("Short read: got $totalRead, expected $contentLength")
                    )
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
        when {
            isMangaBatPageOrAsset(url) -> MANGABAT_REFERER
            url.contains("manganato") -> MANGANATO_REFERER
            url.contains("asurascans") || url.contains("asuracomic") -> "https://asurascans.com/"
            else -> {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}/"
            }
        }
    } catch (e: Exception) {
        url
    }

    private fun isMangaBatPageOrAsset(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase()
        return url.contains("mangabat", ignoreCase = true) ||
            host == "2xstorage.com" ||
            host?.endsWith(".2xstorage.com") == true ||
            host == "waitst.com" ||
            host?.endsWith(".waitst.com") == true
    }
}
