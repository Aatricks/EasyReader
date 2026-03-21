package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.repository.content.EpubContentLoader
import io.aatricks.novelscraper.data.repository.content.LocalContentLoader
import io.aatricks.novelscraper.data.repository.content.PdfContentLoader
import io.aatricks.novelscraper.data.repository.content.WebContentLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
class ContentRepositoryUrlTest {

    // Since we are only testing URL logic which is in companion object or utils, 
    // we can instantiate with mocks easily.
    // However, the logic is now inside ContentRepository's private helper or moved out?
    // Let's check ContentRepository logic. It delegates adjustChapterUrl.
    
    private val webLoader = mock(WebContentLoader::class.java)
    private val pdfLoader = mock(PdfContentLoader::class.java)
    private val epubLoader = mock(EpubContentLoader::class.java)
    private val localLoader = mock(LocalContentLoader::class.java)

    private val repository = ContentRepository(
        webLoader,
        pdfLoader,
        epubLoader,
        localLoader
    )

    @Test
    fun verifyChapterUrlLogic() = runTest {
        assertEquals("http://example.com/chapter-2", repository.incrementChapterUrl("http://example.com/chapter-1"))
        assertEquals("http://example.com/ch2", repository.incrementChapterUrl("http://example.com/ch1"))
        assertEquals("http://example.com/chapter_2", repository.incrementChapterUrl("http://example.com/chapter_1"))
        
        assertEquals("http://example.com/chapter-1", repository.decrementChapterUrl("http://example.com/chapter-2"))
        
        assertNull(repository.decrementChapterUrl("http://example.com/chapter-1"))
    }

            @Test
            fun clearCachesForUrls_deduplicates_and_routes_by_type() = runTest {
                val cleared = repository.clearCachesForUrls(
                    listOf(
                        "https://example.com/chapter-1",
                        "https://example.com/chapter-1",
                        "file:///tmp/book.epub",
                        "file:///tmp/chapter.pdf",
                        "content://com.example.provider/item",
                        "  "
                    )
                )

                assertEquals(4, cleared)
                verify(epubLoader, times(1)).clearCache("file:///tmp/book.epub")
                verify(pdfLoader, times(1)).clearCache("file:///tmp/chapter.pdf")
                verify(pdfLoader, times(1)).clearCache("content://com.example.provider/item")
                verify(webLoader, times(3)).clearCache(org.mockito.kotlin.any())
            }
}
