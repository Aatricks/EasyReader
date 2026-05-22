package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import io.aatricks.easyreader.util.CacheKeyUtils
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

        // Permanent failures (4xx) are accounted for via the .failed sidecar so the chapter
        // counts as complete and we don't keep hammering the dead URL during this pass —
        // hence only one network request. However the user-facing retry button stays
        // available (isRetryable=true) because a 4xx can be a transient CDN response that
        // clears on a manual retry; clearing the sidecar will trigger a fresh attempt.
        assertTrue(result.isComplete)
        assertTrue(result.isRetryable)
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
    fun `legacy sidecar with valid timestamp is imported into store and deleted`() = runBlocking {
        val chapterUrl = "https://example.com/legacy-with-ts"
        val imageUrl = "https://example.com/legacy-ts.jpg"
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

        // Pre-populate the chapter HTML cache so the sidecar location is meaningful, then
        // write a legacy timestamped sidecar file to model the pre-Room-migration state.
        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        store.clear(chapterUrl)
        val sidecar = File(harness.htmlDownloadsDir, "${CacheKeyUtils.keyFor(chapterUrl)}.html.failed")
        val freshTimestamp = System.currentTimeMillis()
        sidecar.writeText("$imageUrl|$freshTimestamp")

        val attemptsBefore = imageRequests.get()
        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)

        // After migration the sidecar should be gone and the store should hold the entry.
        assertFalse("sidecar must be removed after migration import", sidecar.exists())
        assertTrue(
            "imported sidecar entry must suppress further requests during the same pass",
            imageRequests.get() == attemptsBefore
        )
        assertEquals(setOf(imageUrl), store.load(chapterUrl, freshAfterMs = freshTimestamp - 1))
    }

    @Test
    fun `legacy sidecar without timestamp is dropped on migration`() = runBlocking {
        val chapterUrl = "https://example.com/legacy-no-ts"
        val imageUrl = "https://example.com/legacy-no-ts.jpg"
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

        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)
        store.clear(chapterUrl)
        val sidecar = File(harness.htmlDownloadsDir, "${CacheKeyUtils.keyFor(chapterUrl)}.html.failed")
        sidecar.writeText(imageUrl) // plain url, no timestamp — pre-migration format

        val attemptsBefore = imageRequests.get()
        harness.loader.prefetch(chapterUrl, PrefetchMode.USER_REQUESTED)

        assertFalse("sidecar must be removed after migration", sidecar.exists())
        assertTrue(
            "legacy entry without timestamp must trigger a fresh attempt",
            imageRequests.get() > attemptsBefore
        )
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
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val loader = WebContentLoader(
            htmlParser, client, ImageCache(mediaCacheDir, mediaDownloadsDir),
            ImageDownloader(client), ParsedContentCache(), htmlCacheDir, htmlDownloadsDir,
            store, fakeImageDimensionCacheRepository()
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
