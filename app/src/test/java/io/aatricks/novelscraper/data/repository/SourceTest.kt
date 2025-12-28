package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.source.GenericSource
import io.aatricks.novelscraper.data.repository.source.MangaBatSource
import io.aatricks.novelscraper.data.repository.source.MangaDemonSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class SourceTest {

    @Test
    fun testMangaBatSourceInstantiation() {
        val source = MangaBatSource()
        assertEquals("MangaBat", source.name)
        assertTrue(source.baseUrl.contains("mangabat"))
    }

    @Test
    fun testMangaDemonSourceInstantiation() {
        val source = MangaDemonSource()
        assertEquals("MangaDemon", source.name)
        assertEquals("https://mangademon.com", source.baseUrl)
    }

    @Test
    fun testGenericSourceInstantiation() {
        val url = "https://example.com"
        val source = GenericSource(url)
        assertEquals("Custom Source", source.name)
        assertEquals(url, source.baseUrl)
    }
}
