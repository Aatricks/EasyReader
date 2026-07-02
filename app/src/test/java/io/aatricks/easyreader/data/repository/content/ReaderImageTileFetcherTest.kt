package io.aatricks.easyreader.data.repository.content

import coil3.request.Options
import io.aatricks.easyreader.data.repository.ContentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

class ReaderImageTileFetcherTest {

    private val contentRepository: ContentRepository = mock()
    private val options: Options = mock()

    @Test
    fun `resolveFile returns local file for file uri without touching cache or network`() = runTest {
        val tempDir = Files.createTempDirectory("easyreader_test")
        val tempFile = tempDir.resolve("image.jpg").toFile()
        tempFile.writeText("fake content")

        try {
            val tile = ReaderImageTile(
                imageUrl = tempFile.toURI().toString(),
                pageUrl = "https://example.com/page",
                sliceIndex = 0,
                sliceCount = 2
            )
            val fetcher = ReaderImageTileFetcher(tile, contentRepository, options)

            val result = fetcher.resolveFile()

            assertEquals(tempFile.absolutePath, result?.absolutePath)
            verify(contentRepository, never()).findUsableCachedMediaFile(any())
            verify(contentRepository, never()).downloadAndCacheImage(any(), any())
        } finally {
            tempFile.delete()
            tempDir.toFile().delete()
        }
    }

    @Test
    fun `resolveFile falls back to cache lookup when file uri target is missing`() = runTest {
        val tempDir = Files.createTempDirectory("easyreader_test")
        val tempFile = tempDir.resolve("nonexistent.jpg").toFile()
        // Ensure it doesn't exist
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val fileUri = tempFile.toURI().toString()

        try {
            whenever(contentRepository.findUsableCachedMediaFile(fileUri)).thenReturn(null)
            whenever(contentRepository.downloadAndCacheImage(eq(fileUri), any())).thenReturn(null)

            val tile = ReaderImageTile(
                imageUrl = fileUri,
                pageUrl = "https://example.com/page",
                sliceIndex = 0,
                sliceCount = 2
            )
            val fetcher = ReaderImageTileFetcher(tile, contentRepository, options)

            val result = fetcher.resolveFile()

            assertNull(result)
            verify(contentRepository).findUsableCachedMediaFile(fileUri)
        } finally {
            tempDir.toFile().delete()
        }
    }

    @Test
    fun `resolveFile uses cache then network for http urls`() = runTest {
        val httpUrl = "https://example.com/image.jpg"
        val pageUrl = "https://example.com/page"
        val tempDir = Files.createTempDirectory("easyreader_test")
        val cachedFile = tempDir.resolve("cached.jpg").toFile()
        cachedFile.writeText("cached data")

        try {
            whenever(contentRepository.findUsableCachedMediaFile(httpUrl)).thenReturn(null)
            whenever(contentRepository.downloadAndCacheImage(httpUrl, pageUrl)).thenReturn(cachedFile)

            val tile = ReaderImageTile(
                imageUrl = httpUrl,
                pageUrl = pageUrl,
                sliceIndex = 0,
                sliceCount = 2
            )
            val fetcher = ReaderImageTileFetcher(tile, contentRepository, options)

            val result = fetcher.resolveFile()

            assertEquals(cachedFile.absolutePath, result?.absolutePath)
            verify(contentRepository).findUsableCachedMediaFile(httpUrl)
            verify(contentRepository).downloadAndCacheImage(httpUrl, pageUrl)
        } finally {
            cachedFile.delete()
            tempDir.toFile().delete()
        }
    }
}
