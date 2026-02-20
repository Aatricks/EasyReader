package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.ContentElement
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class ContentRepositoryBenchmarkTest {

    @Test
    fun benchmarkBackgroundCacheImages() {
        val imageCount = 5000
        val latch = CountDownLatch(imageCount)

        // Setup mocks
        val mockContext = mock<Context>()
        val mockHtmlParser = mock<HtmlParser>()
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "bench_cache_${System.currentTimeMillis()}")
        val mediaCacheDir = File(cacheDir, "media_cache")
        cacheDir.mkdirs()
        mediaCacheDir.mkdirs()

        whenever(mockContext.cacheDir).thenReturn(cacheDir)

        // Interceptor that counts down and returns immediately
        val interceptor = Interceptor { chain ->
            latch.countDown()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(10).toResponseBody("image/jpeg".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = imageCount * 2
                maxRequestsPerHost = imageCount * 2
            })
            .addInterceptor(interceptor)
            .build()

        val repository = ContentRepository(mockContext, mockHtmlParser, okHttpClient)

        // Generate images
        val images = (1..imageCount).map {
            ContentElement.Image("http://example.com/img_$it.jpg", width = 100, height = 100)
        }

        println("Starting benchmark with $imageCount images...")

        val time = measureTimeMillis {
            repository.backgroundCacheImages(images, "http://example.com/chapter1")

            // Wait for all to finish
            val completed = latch.await(30, TimeUnit.SECONDS)
            if (!completed) {
                println("WARNING: Benchmark timed out! Pending: ${latch.count}")
            }
        }

        println("Benchmark completed in ${time}ms")

        // Cleanup
        cacheDir.deleteRecursively()
    }
}
