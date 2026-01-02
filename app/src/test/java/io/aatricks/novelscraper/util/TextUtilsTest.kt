package io.aatricks.novelscraper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun testFormatChapterText() {
        // Test case 1: Basic formatting
        val input1 = "  Para graph 1.  \n\n\n   Paragraph 2. \n\n  "
        val expected1 = "Para graph 1.\n\nParagraph 2."
        assertEquals(expected1, TextUtils.formatChapterText(input1))

        // Test case 2: Multiple line breaks and spaces
        val input2 = "First line. \r\n\r\n\n\n Second line with multiple    spaces."
        val expected2 = "First line.\n\nSecond line with multiple spaces."
        assertEquals(expected2, TextUtils.formatChapterText(input2))

        // Test case 3: No changes needed
        val input3 = "This is a single line."
        val expected3 = "This is a single line."
        assertEquals(expected3, TextUtils.formatChapterText(input3))

        // Test case 4: Empty input
        val input4 = ""
        val expected4 = ""
        assertEquals(expected4, TextUtils.formatChapterText(input4))

        // Test case 5: Whitespace only
        val input5 = "   \n\n\t  "
        val expected5 = ""
        assertEquals(expected5, TextUtils.formatChapterText(input5))

        // Test case 6: HTML-like input
        val input6 = "‘No.’<br><br>I processed a dozen responses to Fate’s simple statement.<br><br>Then a dozen likely answers to each response. And my counter to each of<br><br>Fate’s answers."
        val expected6 = "‘No.’\n\nI processed a dozen responses to Fate’s simple statement.\n\nThen a dozen likely answers to each response. And my counter to each of\n\nFate’s answers."
        val formattedWithBr = input6.replace("<br>", "\n")
        val actual6 = TextUtils.formatChapterText(formattedWithBr)
        assertEquals(expected6, actual6)
    }

    @Test
    fun testUrlNavigation() {
        val url = "https://novelfire.net/book/novel-title/chapter-5"
        assertEquals("https://novelfire.net/book/novel-title/chapter-6", TextUtils.incrementChapterInUrl(url))
        assertEquals("https://novelfire.net/book/novel-title/chapter-4", TextUtils.decrementChapterInUrl(url))
        
        val urlWithDots = "https://example.com/123.html"
        assertEquals("https://example.com/124.html", TextUtils.incrementChapterInUrl(urlWithDots))
        
        val urlAtOne = "https://example.com/ch-1"
        assertEquals("https://example.com/ch-2", TextUtils.incrementChapterInUrl(urlAtOne))
        assertEquals("https://example.com/ch-1", TextUtils.decrementChapterInUrl(urlAtOne))
    }

    @Test
    fun testExtractTitleFromUrl() {
        assertEquals("Novel Title", TextUtils.extractTitleFromUrl("https://novelfire.net/book/novel-title"))
        assertEquals("Mercenary Enrollment", TextUtils.extractTitleFromUrl("https://www.mangabats.com/manga/mercenary-enrollment"))
        assertEquals("Chapter 5", TextUtils.extractTitleFromUrl("https://example.com/chapter-5/"))
    }

    @Test
    fun testExtractChapterNumber() {
        assertEquals(5, TextUtils.extractChapterNumber("Chapter 5: The Battle"))
        assertEquals(123, TextUtils.extractChapterNumber("https://example.com/manga/123"))
        assertEquals(1, TextUtils.extractChapterNumber("CH 1"))
        assertEquals(null, TextUtils.extractChapterNumber("No Number Here"))
    }

    @Test
    fun testRemovePageNumbers() {
        val pdfText = "Line one\n123\nLine two"
        // PDF filtering is aggressive, might remove numbers in the middle
        assertTrue(TextUtils.removePageNumbers(pdfText, true).contains("Line one"))
        assertTrue(TextUtils.removePageNumbers(pdfText, true).contains("Line two"))
        
        val pageWordText = "Page | 123 Some content"
        assertEquals("123 Some content", TextUtils.removePageWord(pageWordText))
    }

    @Test
    fun testWordCountAndReadingTime() {
        val text = "This is a simple sentence with seven words."
        assertEquals(8, TextUtils.countWords(text))
        assertEquals(1, TextUtils.estimateReadingTime(text))
        
        val longText = (1..400).joinToString(" ") { "word" }
        assertEquals(400, TextUtils.countWords(longText))
        assertEquals(2, TextUtils.estimateReadingTime(longText))
    }

    @Test
    fun testCleanHtmlEntities() {
        assertEquals("&", TextUtils.cleanHtmlEntities("&amp;"))
        assertEquals("\"Quote\"", TextUtils.cleanHtmlEntities("&quot;Quote&quot;"))
        assertEquals("—", TextUtils.cleanHtmlEntities("&mdash;"))
    }
}