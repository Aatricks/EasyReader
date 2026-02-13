package io.aatricks.novelscraper.data.repository

import android.content.Context
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.assertEquals
import org.mockito.Mockito.mock
import kotlinx.coroutines.runBlocking

class ContentRepositoryUrlTest {

    @Test
    fun verifyChapterUrlLogic() {
        // Mock dependencies
        val context = mock(Context::class.java)
        val htmlParser = mock(HtmlParser::class.java)
        val okHttpClient = mock(OkHttpClient::class.java)
        val repository = ContentRepository(context, htmlParser, okHttpClient)

        runBlocking {
            val url = "https://example.com/chapter-100"
            val next = repository.incrementChapterUrl(url)
            assertEquals("https://example.com/chapter-101", next)

            val prev = repository.decrementChapterUrl("https://example.com/chapter-101")
            assertEquals("https://example.com/chapter-100", prev)

            val url2 = "https://example.com/ch-50"
            val next2 = repository.incrementChapterUrl(url2)
            assertEquals("https://example.com/ch-51", next2)
        }
    }
}
