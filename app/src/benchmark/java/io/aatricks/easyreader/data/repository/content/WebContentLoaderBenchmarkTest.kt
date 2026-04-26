package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.HtmlParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WebContentLoaderBenchmarkTest {

    @Test
    fun benchmarkImageRequestsSerialization() = runBlocking {
        val pageUrl = "https://example.com/chapter-host-gate"
        val imageUrls = listOf(
            "https://cdn.example.com/a.jpg",
            "https://cdn.example.com/b.jpg"
        )
        val activeRequests = AtomicInteger(0)
        val maxConcurrentRequests = AtomicInteger(0)
        val firstRequestAt = AtomicLong(0)
        val secondRequestAt = AtomicLong(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                if (request.url.host == "cdn.example.com") {
                    val startedAt = System.currentTimeMillis()
                    if (request.url.toString() == imageUrls[0]) {
                        firstRequestAt.compareAndSet(0, startedAt)
                    } else {
                        secondRequestAt.compareAndSet(0, startedAt)
                    }
                    val current = activeRequests.incrementAndGet()
                    maxConcurrentRequests.updateAndGet { maxOf(it, current) }
                    try {
                        Thread.sleep(120)
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                    buildByteResponse(request, tinyPng(width = 2, height = 3), "image/png")
                } else {
                    buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        listOf(
            async { loader.downloadAndCacheImage(imageUrls[0], pageUrl) },
            async { loader.downloadAndCacheImage(imageUrls[1], pageUrl) }
        ).awaitAll()

        assertEquals(1, maxConcurrentRequests.get())
        assertTrue(secondRequestAt.get() - firstRequestAt.get() >= 100)
    }

    @Test
    fun benchmarkPrefetchRetryPacing() = runBlocking {
        val chapterUrl = "https://example.com/chapter-rate-limit"
        val imageUrl = "https://example.com/rate-limited.jpg"
        val htmlParser = mock<HtmlParser>()
        val imageAttempts = AtomicInteger(0)
        val startedAt = System.currentTimeMillis()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(
                        request,
                        "<html><head><title>Rate Limit</title></head><body></body></html>",
                        "text/html"
                    )

                    imageUrl -> {
                        val attempt = imageAttempts.incrementAndGet()
                        if (attempt == 1) {
                            buildResponse(request, "", "text/plain", code = 429)
                                .newBuilder()
                                .header("Retry-After", "1")
                                .build()
                        } else {
                            buildByteResponse(request, tinyPng(width = 2, height = 3), "image/png")
                        }
                    }

                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Image(imageUrl)))

        val result = loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertEquals(2, imageAttempts.get())
        assertTrue("Expected Retry-After pacing, elapsed=$elapsedMs", elapsedMs >= 900)
        assertTrue(result.isComplete)
    }

    private fun createLoader(
        htmlParser: HtmlParser,
        interceptor: Interceptor
    ): WebContentLoader {
        val root = Files.createTempDirectory("web-loader-bench").toFile()
        val htmlCacheDir = File(root, "html_cache").apply { mkdirs() }
        val mediaCacheDir = File(root, "media_cache").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val imageCache = ImageCache(mediaCacheDir)
        val imageDownloader = ImageDownloader(client)
        return WebContentLoader(htmlParser, client, imageCache, imageDownloader, htmlCacheDir)
    }

    private fun buildResponse(
        request: okhttp3.Request,
        body: String,
        contentType: String,
        code: Int = 200
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun buildByteResponse(
        request: okhttp3.Request,
        body: ByteArray,
        contentType: String,
        code: Int = 200
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun tinyPng(width: Int, height: Int): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val ihdrData = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8)
            .put(2)
            .put(0)
            .put(0)
            .put(0)
            .array()
        return signature +
            pngChunk("IHDR", ihdrData) +
            pngChunk("IEND", byteArrayOf())
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + data.size + 4)
            .putInt(data.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(data)
            .putInt(0)
            .array()
    }
}
