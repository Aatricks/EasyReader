package io.aatricks.easyreader.data.repository.content

import coil3.ImageLoader
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.aatricks.easyreader.data.repository.ContentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files

class EpubImageFetcherTest {

    private val contentRepository: ContentRepository = mock()

    @Test
    fun `factory creates fetcher for epub image url`() = runTest {
        val factory = EpubImageFetcher.Factory(contentRepository)
        val options: Options = mock()
        val imageLoader: ImageLoader = mock()
        
        val fetcher = factory.create("path/to.epub#img:images/1.jpg", options, imageLoader)
        assertNotNull(fetcher)
        assertTrue(fetcher is EpubImageFetcher)
    }

    @Test
    fun `factory returns null for non-epub url`() = runTest {
        val factory = EpubImageFetcher.Factory(contentRepository)
        val options: Options = mock()
        val imageLoader: ImageLoader = mock()
        
        val fetcher = factory.create("https://example.com/image.jpg", options, imageLoader)
        assertNull(fetcher)
    }

    @Test
    fun `fetcher returns SourceFetchResult on success`() = runTest {
        val url = "path/to.epub#img:images/1.jpg"
        val imageFile = Files.createTempFile("epub-image", ".jpg").toFile()
        imageFile.writeBytes(byteArrayOf(1, 2, 3))
        whenever(contentRepository.getEpubImageFile(url)).thenReturn(imageFile)
        
        val options: Options = mock()
        whenever(options.fileSystem).thenReturn(okio.FileSystem.SYSTEM)

        val fetcher = EpubImageFetcher(url, contentRepository, options)
        val result = fetcher.fetch()
        
        assertTrue(result is SourceFetchResult)
        assertNotNull((result as SourceFetchResult).source)
        imageFile.delete()
    }

    @Test
    fun `fetcher returns null on failure`() = runTest {
        val url = "path/to.epub#img:images/1.jpg"
        whenever(contentRepository.getEpubImageFile(url)).thenReturn(null)
        
        val options: Options = mock()
        
        val fetcher = EpubImageFetcher(url, contentRepository, options)
        val result = fetcher.fetch()
        
        assertNull(result)
    }
}
