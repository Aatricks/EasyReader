package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.local.PreferencesManager
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class NovelFireSourceConcurrencyTest {

    @Test
    fun testLoadAdditionalChapterPagesConcurrency() = runTest {
        val maxConcurrency = AtomicInteger(0)
        val activeRequests = AtomicInteger(0)
        val requestCount = AtomicInteger(0)

        val interceptor = Interceptor { chain ->
            val currentActive = activeRequests.incrementAndGet()
            maxConcurrency.set(max(maxConcurrency.get(), currentActive))

            // Simulate network delay
            Thread.sleep(100)

            val request = chain.request()
            val url = request.url.toString()
            requestCount.incrementAndGet()

            val responseBody = when {
                url.contains("/book/test-novel") && !url.contains("chapters") -> {
                    """
                        <html>
                            <body>
                                <h1 class="novel-title">Test Novel</h1>
                                <a href="/book/test-novel/chapters">Chapters</a>
                            </body>
                        </html>
                    """
                }
                url.contains("/book/test-novel/chapters") -> {
                    // Page 1 or implicit page 1
                    val page = if (url.contains("page=")) url.substringAfter("page=").toInt() else 1
                    if (page == 1) {
                        """
                            <html>
                                <body>
                                    <ul class="chapter-list">
                                        <li><a href="/chapter-1">Chapter 1</a></li>
                                    </ul>
                                    <ul class="pagination">
                                        <li class="page-item"><a class="page-link" href="?page=10">10</a></li>
                                    </ul>
                                </li>
                            </html>
                        """
                    } else {
                        """
                            <html>
                                <body>
                                    <ul class="chapter-list">
                                        <li><a href="/chapter-$page">Chapter $page</a></li>
                                    </ul>
                                </body>
                            </html>
                        """
                    }
                }
                else -> ""
            }

            activeRequests.decrementAndGet()

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.trimIndent().toResponseBody("text/html".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val preferencesManager = mock(PreferencesManager::class.java)
        val source = NovelFireSource(preferencesManager, okHttpClient)

        source.getNovelDetails("https://novelfire.net/book/test-novel")

        // With 10 pages total (1 initial + 9 additional), and unbounded concurrency,
        // we expect maxConcurrency to be close to 9 (since they are launched together).
        // The initial page load is sequential, then 2..10 are launched.
        // So concurrent requests should be at least > 5 to prove it is unbounded.
        println("Max concurrency: ${maxConcurrency.get()}")
        println("Total requests: ${requestCount.get()}")

        // Assert that we made requests for all pages (1 initial + 1 chapters page + 9 additional pages = 11 requests?
        // No.
        // 1. getNovelDetails -> getDocument(url) -> 1 request
        // 2. getChaptersUrl -> returns .../chapters
        // 3. getDocument(chaptersUrl) -> 1 request (Page 1)
        // 4. extractMaxPage -> returns 10
        // 5. loadAdditionalChapterPages(2..10) -> 9 requests
        // Total = 1 + 1 + 9 = 11 requests.

        assertTrue("Should have made at least 11 requests", requestCount.get() >= 11)

        // This assertion documents the ISSUE.
        // If concurrency is unbounded, it will be high.
        // We want to prove it IS high now, and will be LOW later.
        // But for a regression test, we should assert it is LOW.
        // Since I'm creating the test now, I will assert it is HIGH to confirm the issue,
        // then I will change the code and update the test to assert it is LOW.
        // OR I can just log it and rely on my observation.
        // Better: Assert that it is unbounded (e.g. > 3) to prove the test setup works.
        // Then when I fix it, I'll update the assertion to be <= 5.
        // Actually, if I change the code, this test will fail if I assert > 3.
        // So I should write the test as if it's the final verification.
        // But first I want to see it fail (or pass with high concurrency).

        // Verify concurrency is limited
        assertTrue("Max concurrency should be limited (was ${maxConcurrency.get()})", maxConcurrency.get() <= 5)

        // Verify we got all chapters (9 additional pages * 1 chapter each + 1 from page 1 = 10 chapters found?)
        // The mock returns 1 chapter per page.
        // Page 1 has 1 chapter.
        // Pages 2..10 have 1 chapter each.
        // Total = 10 chapters.
        // However, this test just calls getNovelDetails, which returns an ExploreItem.
        // ExploreItem has a list of chapters.
        // We should verify that too if we want to be thorough, but checking requestCount is enough for now.
    }
}
