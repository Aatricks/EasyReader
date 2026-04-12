package io.aatricks.novelscraper.data.repository.content

import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.PrefetchMode
import io.aatricks.novelscraper.data.repository.HtmlParser
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

class WebContentLoaderTest {

    @Test
    fun `loadWebContent uses cached html without range probes for image dimensions`() = runBlocking {
        val chapterUrl = "https://example.com/chapter-1"
        val imageUrl = "https://example.com/image-1.jpg"
        val rangeRequests = AtomicInteger(0)
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (request.header("Range") != null) {
                    rangeRequests.incrementAndGet()
                }
                when (url) {
                    chapterUrl -> buildResponse(request, "<html><head><title>Chapter 1</title></head><body></body></html>", "text/html")
                    imageUrl -> buildResponse(request, "fake-image-binary", "image/jpeg")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(ContentElement.Image(imageUrl))
        )
        loader.getCachedFile(chapterUrl).writeText("<html><head><title>Chapter 1</title></head><body></body></html>")

        val result = loader.loadWebContent(chapterUrl)

        assertTrue(result is ContentResult.Success)
        assertEquals(0, rangeRequests.get())
    }

    @Test
    fun `image downloads are single flight across warm and direct cache requests`() = runBlocking {
        val pageUrl = "https://example.com/chapter-2"
        val imageUrl = "https://example.com/image-2.jpg"
        val imageRequests = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                if (request.url.toString() == imageUrl && request.header("Range") == null) {
                    imageRequests.incrementAndGet()
                    Thread.sleep(150)
                    buildResponse(request, "fake-image-binary", "image/jpeg")
                } else {
                    buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        val results = listOf(
            async { loader.downloadAndCacheImage(imageUrl, pageUrl) },
            async { loader.warmImage(imageUrl, pageUrl) }
        ).awaitAll()

        assertEquals(1, imageRequests.get())
        assertTrue(results.all { it != null })
    }

    @Test
    fun `user requested and speculative prefetch report actual cached image coverage`() = runBlocking {
        val userUrl = "https://example.com/chapter-user"
        val speculativeUrl = "https://example.com/chapter-spec"
        val userImages = listOf(
            "https://example.com/u1.jpg",
            "https://example.com/u2.jpg"
        )
        val speculativeImages = listOf(
            "https://example.com/s1.jpg",
            "https://example.com/s2.jpg",
            "https://example.com/s3.jpg",
            "https://example.com/s4.jpg",
            "https://example.com/s5.jpg"
        )
        val speculativeDownloads = AtomicInteger(0)
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when (url) {
                    userUrl -> buildResponse(request, "<html><head><title>User</title></head><body></body></html>", "text/html")
                    speculativeUrl -> buildResponse(request, "<html><head><title>Spec</title></head><body></body></html>", "text/html")
                    userImages[0] -> buildResponse(request, "img-1", "image/jpeg")
                    userImages[1] -> buildResponse(request, "", "text/plain", code = 500)
                    else -> {
                        if (url in speculativeImages && request.header("Range") == null) {
                            speculativeDownloads.incrementAndGet()
                            buildResponse(request, "img-spec", "image/jpeg")
                        } else {
                            buildResponse(request, "", "text/plain", code = 404)
                        }
                    }
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(userUrl))).thenReturn(userImages.map { ContentElement.Image(it) })
        whenever(htmlParser.parse(any(), eq(speculativeUrl))).thenReturn(speculativeImages.map { ContentElement.Image(it) })

        val userRequested = loader.prefetch(userUrl, PrefetchMode.USER_REQUESTED)
        val speculative = loader.prefetch(speculativeUrl, PrefetchMode.SPECULATIVE)

        assertTrue(userRequested.htmlCached)
        assertEquals(2, userRequested.totalImages)
        assertEquals(1, userRequested.cachedImages)
        assertFalse(userRequested.isComplete)

        assertTrue(speculative.htmlCached)
        assertEquals(5, speculative.totalImages)
        assertEquals(3, speculative.cachedImages)
        assertFalse(speculative.isComplete)
        assertEquals(3, speculativeDownloads.get())
    }

    private fun createLoader(
        htmlParser: HtmlParser,
        interceptor: Interceptor
    ): WebContentLoader {
        val root = Files.createTempDirectory("web-loader-test").toFile()
        val htmlCacheDir = File(root, "html_cache").apply { mkdirs() }
        val mediaCacheDir = File(root, "media_cache").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        return WebContentLoader(htmlParser, client, htmlCacheDir, mediaCacheDir)
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
