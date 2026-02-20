package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.ContentElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.File
import kotlin.system.measureTimeMillis

class ContentRepositoryPerformanceTest {

    private lateinit var repository: ContentRepository
    private val mockContext = mock<Context>()
    private val mockHtmlParser = mock<HtmlParser>()
    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "perf_test_cache_${System.currentTimeMillis()}")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        cacheDir.mkdirs()
        whenever(mockContext.cacheDir).thenReturn(cacheDir)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun createRepositoryWithDelay(delayMs: Long): ContentRepository {
        val interceptor = Interceptor { chain ->
            val request = chain.request()

            // Simulate delay only for dimension requests (Range header)
            if (request.header("Range") != null) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(100).toResponseBody("image/jpeg".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 100; maxRequestsPerHost = 100 })
            .addInterceptor(interceptor)
            .build()

        return ContentRepository(mockContext, mockHtmlParser, okHttpClient)
    }

    @Test
    fun testLoadContentPerformance_LargeChapter_Optimized() = runBlocking {
        repository = createRepositoryWithDelay(50)

        // Mock HtmlParser to return 100 images (above threshold)
        val images = (1..100).map { ContentElement.Image("http://example.com/img$it.jpg", width = 0, height = 0) }
        whenever(mockHtmlParser.parse(any(), any())).thenReturn(images)

        // Measure time
        val time = measureTimeMillis {
            repository.loadContent("http://example.com/manhwa-chapter-1")
        }

        println("Time taken for 100 images (Optimized): ${time}ms")

        // Expectation: Should be fast (skipped dimension check)
        // Overhead should be minimal (< 200ms)
        assertTrue("Expected < 200ms, got ${time}ms", time < 200)
    }

    @Test
    fun testLoadContentPerformance_ManhwaUrl_Optimized() = runBlocking {
        repository = createRepositoryWithDelay(50)

        // Mock HtmlParser to return 20 images (below threshold, but URL is manhwa)
        val images = (1..20).map { ContentElement.Image("http://example.com/img$it.jpg", width = 0, height = 0) }
        whenever(mockHtmlParser.parse(any(), any())).thenReturn(images)

        // Measure time
        val time = measureTimeMillis {
            repository.loadContent("http://example.com/manhwa-chapter-1")
        }

        println("Time taken for 20 images with Manhwa URL (Optimized): ${time}ms")

        // Expectation: Should be fast (skipped dimension check due to URL)
        assertTrue("Expected < 200ms, got ${time}ms", time < 200)
    }

    @Test
    fun testLoadContentPerformance_SmallChapter_Unoptimized() = runBlocking {
        repository = createRepositoryWithDelay(50)

        // Mock HtmlParser to return 10 images (below threshold)
        val images = (1..10).map { ContentElement.Image("http://example.com/img$it.jpg", width = 0, height = 0) }
        whenever(mockHtmlParser.parse(any(), any())).thenReturn(images)

        // Measure time
        val time = measureTimeMillis {
            repository.loadContent("http://example.com/manga-chapter-1")
        }

        println("Time taken for 10 images (Unoptimized): ${time}ms")

        // Expectation: Should take time (fetching dimensions)
        // 10 images / 10 concurrent = 1 batch.
        // 1 batch * 50ms = 50ms minimum.
        assertTrue("Expected >= 50ms, got ${time}ms", time >= 50)
    }
}
