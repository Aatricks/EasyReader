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

@ExperimentalCoroutinesApi
class ContentRepositoryUrlTest {

    // Since we are only testing URL logic which is in companion object or utils, 
    // we can instantiate with mocks easily.
    // However, the logic is now inside ContentRepository's private helper or moved out?
    // Let's check ContentRepository logic. It delegates adjustChapterUrl.
    
    private val repository = ContentRepository(
        mock(WebContentLoader::class.java),
        mock(PdfContentLoader::class.java),
        mock(EpubContentLoader::class.java),
        mock(LocalContentLoader::class.java)
    )

    @Test
    fun verifyChapterUrlLogic() = runTest {
        assertEquals("http://example.com/chapter-2", repository.incrementChapterUrl("http://example.com/chapter-1"))
        assertEquals("http://example.com/ch2", repository.incrementChapterUrl("http://example.com/ch1"))
        assertEquals("http://example.com/chapter_2", repository.incrementChapterUrl("http://example.com/chapter_1"))
        
        assertEquals("http://example.com/chapter-1", repository.decrementChapterUrl("http://example.com/chapter-2"))
        
        assertNull(repository.decrementChapterUrl("http://example.com/chapter-1"))
    }
}
