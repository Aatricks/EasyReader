package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.HtmlParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class WebContentLoaderPrefetchRetryTest {

    @Test
    fun `prefetch stops immediately on 404 permanent image failure`() = runBlocking {
        val chapterUrl = "https://example.com/permanent-fail"
        val imageUrl = "https://example.com/404.jpg"
        val htmlParser = mock<HtmlParser>()
        val imageRequests = AtomicInteger(0)
        
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(request, "<html><body></body></html>", "text/html")
                    imageUrl -> {
                        imageRequests.incrementAndGet()
                        buildResponse(request, "Not Found", "text/plain", code = 404)
                    }
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Image(imageUrl)))

        val result = withTimeout(5000) {
            loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        }

        // Permanent failures do NOT mark the chapter complete — strict completion requires
        // every image to be successfully cached. The permanent-failure sidecar still
        // suppresses network retries, so the chapter is honest-incomplete-but-not-retryable
        // and we only see one request.
        assertFalse(result.isComplete)
        assertFalse(result.isRetryable)
        assertEquals(1, imageRequests.get())
    }

    @Test
    fun `prefetch retries on transient failures up to max attempts`() = runBlocking {
        val chapterUrl = "https://example.com/transient-fail"
        val imageUrl = "https://example.com/500.jpg"
        val htmlParser = mock<HtmlParser>()
        val imageRequests = AtomicInteger(0)
        
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(request, "<html><body></body></html>", "text/html")
                    imageUrl -> {
                        imageRequests.incrementAndGet()
                        buildResponse(request, "Server Error", "text/plain", code = 500)
                    }
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Image(imageUrl)))

        val result = withTimeout(60000) {
            loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        }

        assertFalse(result.isComplete)
        assertTrue(result.isRetryable)
        // Expected: USER_REQUEST_PREFETCH_PASSES (2) * USER_REQUEST_ATTEMPTS (3) = 6
        assertEquals("Should retry 6 times total (2 passes * 3 image attempts)", 6, imageRequests.get())
    }

    private fun createLoader(
        htmlParser: HtmlParser,
        interceptor: Interceptor
    ): WebContentLoader {
        val root = Files.createTempDirectory("prefetch-retry-test").toFile()
        val htmlCacheDir = File(root, "html_cache").apply { mkdirs() }
        val mediaCacheDir = File(root, "media_cache").apply { mkdirs() }
        val htmlDownloadsDir = File(root, "html_downloads").apply { mkdirs() }
        val mediaDownloadsDir = File(root, "media_downloads").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return WebContentLoader(htmlParser, client, ImageCache(mediaCacheDir, mediaDownloadsDir), ImageDownloader(client), htmlCacheDir, htmlDownloadsDir)
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
}
