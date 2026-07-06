package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.util.CacheKeyUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var downloadsDir: File
    private lateinit var imageCache: ImageCache

    @Before
    fun setup() {
        cacheDir = tempFolder.newFolder("media_cache")
        downloadsDir = tempFolder.newFolder("media_downloads")
        imageCache = ImageCache(cacheDir, downloadsDir)
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
        legacyFile.writeBytes(validJpegBytes())

        val actual = imageCache.getCachedMediaFile(url)
        assertEquals(legacyFile.absolutePath, actual.absolutePath)
    }

    @Test
    fun `getCachedMediaFile prefers primary file if both exist`() {
        val url = "https://example.com/image.jpg"
        val primaryFile = File(cacheDir, CacheKeyUtils.keyFor(url))
        val legacyFile = File(cacheDir, url.hashCode().toString())
        primaryFile.writeBytes(validJpegBytes())
        legacyFile.writeBytes(validJpegBytes())

        val actual = imageCache.getCachedMediaFile(url)
        assertEquals(primaryFile.absolutePath, actual.absolutePath)
    }

    private fun validJpegBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(60) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

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

    @Test
    fun `getLikelyMediaState memoizes until invalidated`() {
        val url = "https://example.com/memo.jpg"
        assertEquals("missing", imageCache.getLikelyMediaState(url))

        // File appears on disk behind the memo's back: the memoized "missing" is served...
        val file = File(cacheDir, CacheKeyUtils.keyFor(url)).apply { writeBytes(validJpegBytes()) }
        assertEquals("missing", imageCache.getLikelyMediaState(url))

        // ...until the url is invalidated (as every production write path does).
        imageCache.invalidateMediaState(url)
        val state = imageCache.getLikelyMediaState(url)
        assertTrue(state.startsWith(file.absolutePath))
    }

    @Test
    fun `deleteCachedMediaFiles refreshes media state`() {
        val url = "https://example.com/delete-refresh.jpg"
        File(cacheDir, CacheKeyUtils.keyFor(url)).writeBytes(validJpegBytes())
        assertNotEquals("missing", imageCache.getLikelyMediaState(url))

        imageCache.deleteCachedMediaFiles(url)

        assertEquals("missing", imageCache.getLikelyMediaState(url))
    }

    @Test
    fun `clearAll and trimToSize drop the media state memo`() {
        val url = "https://example.com/clear-refresh.jpg"
        File(cacheDir, CacheKeyUtils.keyFor(url)).writeBytes(validJpegBytes())
        assertNotEquals("missing", imageCache.getLikelyMediaState(url))

        imageCache.clearAll()
        assertEquals("missing", imageCache.getLikelyMediaState(url))

        File(cacheDir, CacheKeyUtils.keyFor(url)).writeBytes(validJpegBytes())
        imageCache.invalidateMediaState(url)
        assertNotEquals("missing", imageCache.getLikelyMediaState(url))
        imageCache.trimToSize(0)
        assertEquals("missing", imageCache.getLikelyMediaState(url))
    }

    @Test
    fun `trimToSize keeps the memo when nothing is deleted`() {
        val url = "https://example.com/trim-noop.jpg"
        val file = File(cacheDir, CacheKeyUtils.keyFor(url)).apply { writeBytes(validJpegBytes()) }
        val memoized = imageCache.getLikelyMediaState(url)

        // Delete behind the memo's back, then trim with a budget that deletes nothing:
        // the memo must survive (trim runs every ~30s during prefetch — wiping it on
        // no-op trims would defeat the memoization).
        file.delete()
        imageCache.trimToSize(Long.MAX_VALUE)

        assertEquals(memoized, imageCache.getLikelyMediaState(url))
    }

    @Test
    fun `promoteToDownloads refreshes media state to the downloads path`() {
        val url = "https://example.com/promote-refresh.jpg"
        val key = CacheKeyUtils.keyFor(url)
        File(cacheDir, key).writeBytes(validJpegBytes())
        assertTrue(imageCache.getLikelyMediaState(url).startsWith(File(cacheDir, key).absolutePath))

        imageCache.promoteToDownloads(url)

        assertTrue(imageCache.getLikelyMediaState(url).startsWith(File(downloadsDir, key).absolutePath))
    }

    @Test
    fun `promoteToDownloads replaces invalid existing download target`() {
        val url = "https://example.com/promote.jpg"
        val key = CacheKeyUtils.keyFor(url)
        val source = File(cacheDir, key).apply { writeBytes(validJpegBytes()) }
        val invalidTarget = File(downloadsDir, key).apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60))
        }

        val promoted = imageCache.promoteToDownloads(url)

        assertNotNull(promoted)
        assertEquals(invalidTarget.absolutePath, promoted!!.absolutePath)
        assertFalse(source.exists())
        assertTrue(imageCache.isDownloaded(url))
        assertEquals(validJpegBytes().size.toLong(), invalidTarget.length())
    }
}
