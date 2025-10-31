package io.aatricks.novelscraper.util

import org.junit.Assert.assertEquals
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
        assertEquals(expected6, TextUtils.formatChapterText(formattedWithBr))
    }
}
