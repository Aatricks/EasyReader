package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.util.CacheKeyUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var imageCache: ImageCache

    @Before
    fun setup() {
        cacheDir = tempFolder.newFolder("media_cache")
        imageCache = ImageCache(cacheDir)
    }

    @Test
    fun `getCachedMediaFile returns primary file when nothing exists`() {
        val url = "https://example.com/image.jpg"
        val expected = File(cacheDir, CacheKeyUtils.keyFor(url))
        val actual = imageCache.getCachedMediaFile(url)
        assertEquals(expected.absolutePath, actual.absolutePath)
    }

    @Test
    fun `getCachedMediaFile returns existing legacy file if primary does not exist`() {
        val url = "https://example.com/image.jpg"
        val legacyFile = File(cacheDir, url.hashCode().toString())
        legacyFile.writeText("legacy content")
        
        val actual = imageCache.getCachedMediaFile(url)
        assertEquals(legacyFile.absolutePath, actual.absolutePath)
    }

    @Test
    fun `getCachedMediaFile prefers primary file if both exist`() {
        val url = "https://example.com/image.jpg"
        val primaryFile = File(cacheDir, CacheKeyUtils.keyFor(url))
        val legacyFile = File(cacheDir, url.hashCode().toString())
        primaryFile.writeText("primary content")
        legacyFile.writeText("legacy content")
        
        val actual = imageCache.getCachedMediaFile(url)
        assertEquals(primaryFile.absolutePath, actual.absolutePath)
    }

    @Test
    fun `deleteCachedMediaFiles deletes both primary and legacy files`() {
        val url = "https://example.com/image.jpg"
        val primaryFile = File(cacheDir, CacheKeyUtils.keyFor(url))
        val legacyFile = File(cacheDir, url.hashCode().toString())
        primaryFile.writeText("primary")
        legacyFile.writeText("legacy")
        
        assertTrue(primaryFile.exists())
        assertTrue(legacyFile.exists())
        
        imageCache.deleteCachedMediaFiles(url)
        
        assertFalse(primaryFile.exists())
        assertFalse(legacyFile.exists())
    }

    @Test
    fun `clearAll empties the directory`() {
        File(cacheDir, "file1").writeText("data")
        File(cacheDir, "file2").writeText("data")
        
        imageCache.clearAll()
        
        assertTrue(cacheDir.exists())
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `findExistingCachedMediaFile returns null if no file exists`() {
        val url = "https://example.com/non-existent.jpg"
        val result = imageCache.findExistingCachedMediaFile(url)
        assertEquals(null, result)
    }
}
