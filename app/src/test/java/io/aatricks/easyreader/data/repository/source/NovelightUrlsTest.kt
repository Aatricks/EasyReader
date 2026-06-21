package io.aatricks.easyreader.data.repository.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelightUrlsTest {

    @Test
    fun `chapterId recognises novelight chapter urls only`() {
        assertEquals("191867", NovelightUrls.chapterId("https://novelight.net/book/chapter/191867"))
        assertEquals("191867", NovelightUrls.chapterId("https://www.novelight.net/book/chapter/191867"))
        assertNull(NovelightUrls.chapterId("https://novelight.net/book/pick-me-up"))
        assertNull(NovelightUrls.chapterId("https://other.com/book/chapter/1"))
    }

    @Test
    fun `readChapterUrl builds the ajax endpoint`() {
        assertEquals(
            "https://novelight.net/book/ajax/read-chapter/191867",
            NovelightUrls.readChapterUrl("191867")
        )
    }

    @Test
    fun `requiresXhrHeader is true only for novelight ajax endpoints`() {
        assertTrue(NovelightUrls.requiresXhrHeader("https://novelight.net/book/ajax/read-chapter/1"))
        assertTrue(NovelightUrls.requiresXhrHeader("https://novelight.net/ajax/search-live?search=x"))
        assertFalse(NovelightUrls.requiresXhrHeader("https://novelight.net/book/pick-me-up"))
        assertFalse(NovelightUrls.requiresXhrHeader("https://other.com/book/ajax/read-chapter/1"))
    }

    // sanitizeChapterContent is tested directly because org.json is a no-op stub in plain JVM
    // unit tests (testOptions.unitTests.isReturnDefaultValues), so the JSON-decoding wrapper
    // extractChapterContentHtml can't be exercised here.
    @Test
    fun `sanitizeChapterContent returns clean prose without scripts or ads`() {
        val content =
            "<div class=\"chapter-text clcide\">" +
                "<div>Hello world.</div><div>Second line.</div>" +
                "<div class=\"advertisment\"><script>var x=1;</script>ADTEXT</div>" +
                "</div>"

        val html = NovelightUrls.sanitizeChapterContent(content)

        assertTrue(html != null)
        assertTrue(html!!.contains("Hello world."))
        assertTrue(html.contains("Second line."))
        assertFalse(html.contains("ADTEXT"))
        assertFalse(html.contains("advertisment"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `sanitizeChapterContent returns null for empty or gated content`() {
        assertNull(NovelightUrls.sanitizeChapterContent(null))
        assertNull(NovelightUrls.sanitizeChapterContent(""))
        assertNull(NovelightUrls.sanitizeChapterContent("<div class=\"chapter-text\"></div>"))
    }
}
