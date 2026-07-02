package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
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
    fun `prefetch leaves chapter incomplete on 404 image failure`() = runBlocking {
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

        assertTrue(result.isComplete)
        assertFalse(result.isRetryable)
        assertTrue(result.hasPermanentFailures)
        assertEquals(1, imageRequests.get())
    }

    @Test
    fun `permanent failure store entries expire after ttl and trigger fresh retry`() = runBlocking {
        val chapterUrl = "https://example.com/expiring-permanent"
        val imageUrl = "https://example.com/expiring.jpg"
        val htmlParser = mock<HtmlParser>()
        val imageRequests = AtomicInteger(0)

        val store = InMemoryPermanentFailureStore()
        val harness = createLoaderWithDirs(
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
            },
            store = store
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Image(imageUrl)))

        // Pre-seed an expired permanent-failure entry so the loader's TTL filter ignores it
        // and treats the URL as eligible for a fresh attempt. Models the "CDN was transient"
        // recovery path users hit a day after a transient 4xx was misclassified.
        val twoDaysAgo = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
        store.record(chapterUrl, listOf(imageUrl), recordedAtMs = twoDaysAgo)

        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        assertTrue("expired store entry must trigger a fresh image request", imageRequests.get() >= 1)
    }

    @Test
    fun `failed manifest retry attempts the missing image again`() = runBlocking {
        val chapterUrl = "https://example.com/retry-failed-manifest"
        val imageUrl = "https://example.com/retry-failed.jpg"
        val htmlParser = mock<HtmlParser>()
        val imageRequests = AtomicInteger(0)

        val harness = createLoaderWithDirs(
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

        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        val attemptsBefore = imageRequests.get()
        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)

        assertTrue("retry should attempt the missing image again", imageRequests.get() > attemptsBefore)
    }

    @Test
    fun `zero image web chapter is not marked downloaded`() = runBlocking {
        val chapterUrl = "https://example.com/no-images"
        val htmlParser = mock<HtmlParser>()

        val harness = createLoaderWithDirs(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(request, "<html><body></body></html>", "text/html")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(emptyList())

        val result = harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)

        assertFalse(result.isComplete)
        assertFalse(result.isPersistentDownload)
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
        assertEquals("Should retry once through ImageDownloader user attempts", 3, imageRequests.get())
    }

    private fun createLoader(
        htmlParser: HtmlParser,
        interceptor: Interceptor
    ): WebContentLoader = createLoaderWithDirs(htmlParser, interceptor).loader

    private data class LoaderHarness(
        val loader: WebContentLoader,
        val htmlDownloadsDir: File,
        val store: InMemoryPermanentFailureStore
    )

    private fun createLoaderWithDirs(
        htmlParser: HtmlParser,
        interceptor: Interceptor,
        store: InMemoryPermanentFailureStore = InMemoryPermanentFailureStore()
    ): LoaderHarness {
        val root = Files.createTempDirectory("prefetch-retry-test").toFile()
        val htmlCacheDir = File(root, "html_cache").apply { mkdirs() }
        val mediaCacheDir = File(root, "media_cache").apply { mkdirs() }
        val htmlDownloadsDir = File(root, "html_downloads").apply { mkdirs() }
        val mediaDownloadsDir = File(root, "media_downloads").apply { mkdirs() }
        val webOfflineDir = File(root, "web_offline").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val imageDownloader = ImageDownloader(client)
        val imageCache = ImageCache(mediaCacheDir, mediaDownloadsDir)
        val loader = WebContentLoader(
            htmlParser, client, imageCache,
            imageDownloader, ParsedContentCache(), htmlCacheDir, htmlDownloadsDir,
            store, fakeImageDimensionCacheRepository(),
            WebOfflineChapterStore(webOfflineDir, htmlParser, imageDownloader, imageCache, store)
        )
        return LoaderHarness(loader, htmlDownloadsDir, store)
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
