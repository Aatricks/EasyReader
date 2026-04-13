package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.repository.content.EpubContentLoader
import io.aatricks.novelscraper.data.repository.content.LocalContentLoader
import io.aatricks.novelscraper.data.repository.content.PdfContentLoader
import io.aatricks.novelscraper.data.repository.content.WebContentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ContentRepositoryConcurrencyTest {

    private val tinyPng = ByteBuffer.allocate(8 + 8 + 13 + 4 + 8 + 4)
        .put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        .putInt(13)
        .put("IHDR".toByteArray(Charsets.US_ASCII))
        .put(ByteBuffer.allocate(13).putInt(2).putInt(3).put(8).put(2).put(0).put(0).put(0).array())
        .putInt(0)
        .putInt(0)
        .put("IEND".toByteArray(Charsets.US_ASCII))
        .putInt(0)
        .array()

    @Test
    fun testBackgroundCacheImagesConcurrency() = runBlocking {
        // Setup mocks
        val mockContext = mock<Context>()
        val mockHtmlParser = mock<HtmlParser>()
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "test_cache_${System.currentTimeMillis()}")
        val mediaCacheDir = File(cacheDir, "media_cache")
        val epubCacheDir = File(cacheDir, "epub_cache")
        cacheDir.mkdirs()
        mediaCacheDir.mkdirs()
        epubCacheDir.mkdirs()

        whenever(mockContext.cacheDir).thenReturn(cacheDir)

        val activeRequests = AtomicInteger(0)
        val maxConcurrentRequests = AtomicInteger(0)

        // Custom Interceptor to simulate network delay and track concurrency
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
                if (url.endsWith(".jpg")) {
                    // Skip tracking for dimension check (Range header) to verify background caching limit
                    if (request.header("Range") != null) {
                        return@Interceptor Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(tinyPng.toResponseBody("image/png".toMediaType()))
                            .build()
                    }

                val current = activeRequests.incrementAndGet()
                synchronized(maxConcurrentRequests) {
                    if (current > maxConcurrentRequests.get()) {
                        maxConcurrentRequests.set(current)
                    }
                }

                // Simulate delay to force concurrency
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    // Ignore
                }

                activeRequests.decrementAndGet()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(tinyPng.toResponseBody("image/png".toMediaType()))
                    .build()
            } else {
                // Return dummy HTML
                val response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><body><p>Content</p></body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
                response
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 100; maxRequestsPerHost = 100 }) // Ensure OkHttp doesn't bottleneck us artificially
            .addInterceptor(interceptor)
            .build()

        val webLoader = WebContentLoader(mockHtmlParser, okHttpClient, cacheDir, mediaCacheDir)
        val pdfLoader = PdfContentLoader(mockContext)
        val epubLoader = EpubContentLoader(mockContext, epubCacheDir)
        val localLoader = LocalContentLoader(mockContext, mockHtmlParser, pdfLoader, epubLoader)
        
        val repository = ContentRepository(webLoader, pdfLoader, epubLoader, localLoader)

        // Mock HtmlParser to return many images
        val images = (1..50).map { ContentElement.Image("http://example.com/img$it.jpg", width = 100, height = 100) }
        whenever(mockHtmlParser.parse(any(), any())).thenReturn(images)

        // Call loadContent
        repository.loadContent("http://example.com/chapter1")

        // Wait for background tasks to finish
        // Since loadContent returns early, backgroundCacheImages runs in repositoryScope.
        // We wait enough time for the "unbounded" burst to happen.
        Thread.sleep(2000)

        // Cleanup
        cacheDir.deleteRecursively()

        println("Max concurrent requests: ${maxConcurrentRequests.get()}")

        // Background caching should respect the loader's download semaphore.
        // We allow 1 extra for potential timing/buffering issues.
        assertTrue("Expected limited concurrency (<= 6) but got ${maxConcurrentRequests.get()}", maxConcurrentRequests.get() <= 6)
    }
}
