package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ChapterInfo
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChapterListCacheTest {

    private lateinit var tempDir: File
    private lateinit var cache: ChapterListCache
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("chapter_list_cache_test").toFile()
        cache = ChapterListCache(tempDir, json)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save and load round trip`() {
        val chapters = listOf(
            ChapterInfo("Ch 1", "url1"),
            ChapterInfo("Ch 2", "url2")
        )
        
        cache.save("novel1", "Source1", chapters)
        
        val loaded = cache.load("novel1", "Source1")
        assertNotNull(loaded)
        assertEquals(chapters, loaded?.chapters)
        assertEquals("novel1", loaded?.baseNovelUrl)
        assertEquals("Source1", loaded?.sourceName)
    }

    @Test
    fun `save over existing entry round trip`() {
        val chapters1 = listOf(ChapterInfo("Ch 1", "url1"))
        val chapters2 = listOf(
            ChapterInfo("Ch 1", "url1"),
            ChapterInfo("Ch 2", "url2")
        )

        // First save
        cache.save("novel1", "Source1", chapters1)
        val loaded1 = cache.load("novel1", "Source1")
        assertNotNull(loaded1)
        assertEquals(chapters1, loaded1?.chapters)

        // Save over existing
        cache.save("novel1", "Source1", chapters2)
        val loaded2 = cache.load("novel1", "Source1")
        assertNotNull(loaded2)
        assertEquals(chapters2, loaded2?.chapters)
    }
}
