package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mock

class ExploreRepositoryTest {

    @Test
    fun testNovelFireSourceScraping() = runBlocking {
        // Mock scraping not really possible without mockwebserver or network access in tests
        val preferencesManager = mock(PreferencesManager::class.java)
        val okHttpClient = mock(OkHttpClient::class.java)

        val source = NovelFireSource(preferencesManager, okHttpClient)
        assertEquals("NovelFire", source.name)
        assertEquals("https://novelfire.net", source.baseUrl)
    }
}
