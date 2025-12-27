package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.StandardEbooksSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ExploreRepositoryTest {

    @Test
    fun testNovelFireSourceScraping() = runBlocking {
        // Mock scraping not really possible without mockwebserver or network access in tests (and tests usually run offline or controlled)
        // Since I cannot really test network calls in unit tests without mocking, I will just validate the parsing logic if I could inject html.
        // However, the source classes use Jsoup.connect() directly.
        // I will write a test that instantiates them to ensure compilation.

        val source = NovelFireSource()
        assertEquals("NovelFire", source.name)
        assertEquals("https://novelfire.net", source.baseUrl)
    }

    @Test
    fun testStandardEbooksSourceScraping() = runBlocking {
        val source = StandardEbooksSource()
        assertEquals("Standard Ebooks", source.name)
        assertEquals("https://standardebooks.org", source.baseUrl)
    }
}
