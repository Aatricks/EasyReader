package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.ContentElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ContentRepositoryConcurrencyTest {

    @Test
    fun testBackgroundCacheImagesConcurrency() = runBlocking {
        // Setup mocks
        val mockContext = mock<Context>()
        val mockHtmlParser = mock<HtmlParser>()
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "test_cache_${System.currentTimeMillis()}")
        cacheDir.mkdirs()

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
                        .body(ByteArray(1024).toResponseBody("image/jpeg".toMediaType()))
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
                    .body(ByteArray(1024).toResponseBody("image/jpeg".toMediaType()))
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

        val repository = ContentRepository(mockContext, mockHtmlParser, okHttpClient)

        // Mock HtmlParser to return many images
        val images = (1..50).map { ContentElement.Image("http://example.com/img$it.jpg", width = 100, height = 100) }
        whenever(mockHtmlParser.parse(any(), any())).thenReturn(images)

        // Call loadContent
        // We need to bypass processChapterElements dimension check logic if possible or just assume it happens quickly
        // processChapterElements calls fetchImageDimensions which also hits the interceptor.
        // It uses a Semaphore(10). So initially maxConcurrentRequests will hit 10.
        // Then backgroundCacheImages runs. If unbounded, it will hit > 10.

        repository.loadContent("http://example.com/chapter1")

        // Wait for background tasks to finish
        // Since loadContent returns early, backgroundCacheImages runs in repositoryScope.
        // We wait enough time for the "unbounded" burst to happen.
        Thread.sleep(2000)

        // Cleanup
        cacheDir.deleteRecursively()

        println("Max concurrent requests: ${maxConcurrentRequests.get()}")

        // Optimization verification: Concurrency should be limited by the semaphore (5)
        // We allow 1 extra for potential timing/buffering issues.
        assertTrue("Expected limited concurrency (<= 6) but got ${maxConcurrentRequests.get()}", maxConcurrentRequests.get() <= 6)
    }
}
