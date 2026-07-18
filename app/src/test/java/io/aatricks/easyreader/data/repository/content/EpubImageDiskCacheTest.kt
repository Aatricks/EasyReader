package io.aatricks.easyreader.data.repository.content

import android.content.Context
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImageDiskCacheTest {

    @Test
    fun `epub image is atomically extracted once and reused as a file`() = runTest {
        val root = Files.createTempDirectory("epub-image-cache-test").toFile()
        val epubCache = File(root, "epub_cache").apply { mkdirs() }
        val downloads = File(root, "epub_downloads").apply { mkdirs() }
        val epub = File(root, "book.epub")
        val expected = ByteArray(32_000) { index -> (index % 251).toByte() }
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OEBPS/images/page.jpg"))
            zip.write(expected)
            zip.closeEntry()
        }
        val loader = EpubContentLoader(
            mock<Context>(),
            epubCache,
            downloads,
            fakeImageDimensionCacheRepository()
        )
        val url = "${epub.absolutePath}#img:OEBPS/images/page.jpg"

        val first = loader.getEpubImageFile(url)
        val second = loader.getEpubImageFile(url)

        assertTrue(first != null)
        assertEquals(first, second)
        assertArrayEquals(expected, first!!.readBytes())
        assertTrue(first.absolutePath.startsWith(File(epubCache, "extracted_images").absolutePath))
        assertFalse(File(epubCache, "extracted_images").walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `cached image does not reopen epub zip`() = runTest {
        val root = Files.createTempDirectory("epub-image-cache-hit-test").toFile()
        val epubCache = File(root, "epub_cache").apply { mkdirs() }
        val downloads = File(root, "epub_downloads").apply { mkdirs() }
        val epub = File(root, "book.epub")
        val expected = ByteArray(32_000) { index -> (index % 251).toByte() }
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OEBPS/images/page.jpg"))
            zip.write(expected)
            zip.closeEntry()
        }
        val originalLength = epub.length()
        val originalLastModified = epub.lastModified()
        val loader = EpubContentLoader(
            mock<Context>(),
            epubCache,
            downloads,
            fakeImageDimensionCacheRepository()
        )
        val url = "${epub.absolutePath}#img:OEBPS/images/page.jpg"

        val first = loader.getEpubImageFile(url)
        epub.writeBytes(ByteArray(originalLength.toInt()))
        epub.setLastModified(originalLastModified)
        val second = loader.getEpubImageFile(url)

        assertEquals(first, second)
        assertArrayEquals(expected, second!!.readBytes())
    }
}
