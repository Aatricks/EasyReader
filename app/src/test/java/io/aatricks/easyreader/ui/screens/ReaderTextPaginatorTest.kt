package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderTextPaginatorTest {
    @Test
    fun `short paragraphs share a page when text and spacing fit`() {
        val elements = listOf(
            ContentElement.Text("First"),
            ContentElement.Text("Second"),
            ContentElement.Text("Third")
        )

        val pages = paginateReaderContent(
            elements = elements,
            pageHeightPx = 25f,
            lineHeightPx = 10f,
            paragraphSpacingPx = 5f,
            lineEndsFor = ::singleLine
        )

        assertEquals(2, pages.size)
        assertEquals(listOf("First", "Second"), (pages[0] as ReaderPage.Text).fragments.map { it.text })
        assertEquals(listOf("Third"), (pages[1] as ReaderPage.Text).fragments.map { it.text })
    }

    @Test
    fun `long paragraph splits at measured lines without losing text`() {
        val original = "abcdefghij"

        val pages = paginateReaderContent(
            elements = listOf(ContentElement.Text(original)),
            pageHeightPx = 20f,
            lineHeightPx = 10f,
            paragraphSpacingPx = 0f,
            lineEndsFor = { listOf(3, 6, 10) }
        )

        assertEquals(2, pages.size)
        val fragments = pages.flatMap { (it as ReaderPage.Text).fragments }
        assertEquals(original, fragments.joinToString(separator = "") { it.text })
        assertEquals(0f, pages[0].position.sourceOffsetFraction, FLOAT_TOLERANCE)
        assertEquals(0.6f, pages[1].position.sourceOffsetFraction, FLOAT_TOLERANCE)
    }

    @Test
    fun `paragraph spacing moves next paragraph to a new page when it does not fit`() {
        val pages = paginateReaderContent(
            elements = listOf(ContentElement.Text("First"), ContentElement.Text("Second")),
            pageHeightPx = 24f,
            lineHeightPx = 10f,
            paragraphSpacingPx = 5f,
            lineEndsFor = ::singleLine
        )

        assertEquals(2, pages.size)
    }

    @Test
    fun `non text elements remain dedicated pages`() {
        val image = ContentElement.Image("https://example.com/image.jpg")
        val pages = paginateReaderContent(
            elements = listOf(ContentElement.Text("Before"), image, ContentElement.Text("After")),
            pageHeightPx = 100f,
            lineHeightPx = 10f,
            paragraphSpacingPx = 5f,
            lineEndsFor = ::singleLine
        )

        assertEquals(3, pages.size)
        assertSame(image, (pages[1] as ReaderPage.Element).element)
        assertEquals(1, pages[1].position.sourceIndex)
    }

    @Test
    fun `logical source position resolves to containing generated page`() {
        val pages = paginateReaderContent(
            elements = listOf(ContentElement.Text("abcdefghij"), ContentElement.Text("Next")),
            pageHeightPx = 20f,
            lineHeightPx = 10f,
            paragraphSpacingPx = 11f,
            lineEndsFor = { text -> if (text.length == 10) listOf(3, 6, 10) else singleLine(text) }
        )

        assertEquals(0, readerPageIndexForPosition(pages, sourceIndex = 0, sourceOffsetFraction = 0.2f))
        assertEquals(1, readerPageIndexForPosition(pages, sourceIndex = 0, sourceOffsetFraction = 0.7f))
        assertEquals(2, readerPageIndexForPosition(pages, sourceIndex = 1, sourceOffsetFraction = 0f))
    }

    private fun singleLine(text: String): List<Int> = listOf(text.length)

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
