package io.aatricks.easyreader.ui.screens.reader

import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRenderItemTest {

    @Test
    fun `synthetic 10 plus tile image expands to N outer items`() {
        // Image dimensions 1000 x 25000 px, screenWidth 1000 px -> displayHeight 25000 px
        // MAX_TILE_DISPLAY_PX = 2048 -> (25000 + 2047) / 2048 = 13 tiles
        val tallImage = ContentElement.Image(
            url = "http://example.com/manhwa1.jpg",
            altText = "",
            width = 1000,
            height = 25000,
            side = ContentElement.Image.Side.FULL
        )
        val textElement = ContentElement.Text("End of chapter")
        val content = ChapterContent(
            url = "http://example.com/ch1",
            paragraphs = listOf(tallImage, textElement)
        )

        val items = buildReaderRenderItems(
            content = content,
            isManhwa = true,
            screenWidthPx = 1000
        )

        // 13 tile items + 1 text item = 14 outer render items
        assertEquals(14, items.size)
        val firstTile = items[0]
        assertEquals(0, firstTile.sourceElementIndex)
        assertEquals(0, firstTile.tileIndex)
        assertEquals(13, firstTile.tileCount)
        assertTrue(firstTile.key.contains("manhwa1.jpg"))
        assertTrue(firstTile.key.endsWith("#0/13"))

        val payload = firstTile.payload as RenderPayload.Tile
        assertEquals("http://example.com/manhwa1.jpg#0/13", "${payload.imageUrl}#${payload.tileIndex}/${payload.tileCount}")

        val lastTile = items[12]
        assertEquals(0, lastTile.sourceElementIndex)
        assertEquals(12, lastTile.tileIndex)
        assertEquals(13, lastTile.tileCount)

        val textItem = items[13]
        assertEquals(1, textItem.sourceElementIndex)
        assertNull(textItem.tileIndex)
        assertNull(textItem.tileCount)
    }

    @Test
    fun `source to render position mapping converts whole-source fraction to tile plus local fraction`() {
        val tallImage = ContentElement.Image(
            url = "http://example.com/manhwa1.jpg",
            altText = "",
            width = 1000,
            height = 20000,
            side = ContentElement.Image.Side.FULL
        )
        val content = ChapterContent(
            url = "http://example.com/ch1",
            paragraphs = listOf(tallImage)
        )
        val items = buildReaderRenderItems(content = content, isManhwa = true, screenWidthPx = 1000)
        // 10 tiles (20000 / 2048 -> 10)
        val tileCount = items.first().tileCount!!
        assertEquals(10, tileCount)

        // Whole source fraction 0.45 -> tile index floor(0.45 * 10) = 4, local fraction 0.45 * 10 - 4 = 0.5
        val (renderIndex, localFraction) = findRenderIndexForSource(
            renderItems = items,
            sourceIndex = 0,
            sourceOffsetFraction = 0.45f
        )

        assertEquals(4, renderIndex)
        assertEquals(0.5f, localFraction, 0.001f)
    }

    @Test
    fun `render to source position mapping converts tile and local fraction back to whole-source fraction`() {
        val tallImage = ContentElement.Image(
            url = "http://example.com/manhwa1.jpg",
            altText = "",
            width = 1000,
            height = 20000,
            side = ContentElement.Image.Side.FULL
        )
        val content = ChapterContent(
            url = "http://example.com/ch1",
            paragraphs = listOf(tallImage)
        )
        val items = buildReaderRenderItems(content = content, isManhwa = true, screenWidthPx = 1000)

        // Render index 4 (tile 4), local fraction 0.5f -> whole source fraction (4 + 0.5) / 10 = 0.45f
        val (sourceIndex, sourceFraction) = findSourcePositionForRender(
            renderItems = items,
            renderIndex = 4,
            localOffsetFraction = 0.5f
        )

        assertEquals(0, sourceIndex)
        assertEquals(0.45f, sourceFraction, 0.001f)
    }

    @Test
    fun `non-tiled element mapping passes index and fraction directly`() {
        val p1 = ContentElement.Text("Paragraph 1")
        val p2 = ContentElement.Text("Paragraph 2")
        val content = ChapterContent(url = "http://example.com/ch1", paragraphs = listOf(p1, p2))
        val items = buildReaderRenderItems(content = content, isManhwa = false, screenWidthPx = 1000)

        assertEquals(2, items.size)

        val (renderIdx, localFrac) = findRenderIndexForSource(items, sourceIndex = 1, sourceOffsetFraction = 0.3f)
        assertEquals(1, renderIdx)
        assertEquals(0.3f, localFrac, 0.001f)

        val (srcIdx, srcFrac) = findSourcePositionForRender(items, renderIndex = 1, localOffsetFraction = 0.3f)
        assertEquals(1, srcIdx)
        assertEquals(0.3f, srcFrac, 0.001f)
    }

    private fun chapter(url: String, images: Int, texts: Int) = ChapterContent(
        url = url,
        paragraphs = List(images) {
            ContentElement.Image(
                url = "http://example.com/img$it.jpg",
                altText = "",
                width = 800,
                height = 1200,
                side = ContentElement.Image.Side.FULL
            )
        } + List(texts) { ContentElement.Text("paragraph $it") }
    )

    @Test
    fun `image-heavy chapter uses the manhwa layout`() {
        assertTrue(isManhwaLayout(chapter("http://example.com/ch1", images = 40, texts = 3)))
    }

    @Test
    fun `manhwa keeps its layout despite a handful of credit lines`() {
        assertTrue(isManhwaLayout(chapter("http://example.com/ch1", images = 40, texts = 15)))
    }

    @Test
    fun `prose chapter with a few images keeps paragraph spacing`() {
        assertFalse(isManhwaLayout(chapter("http://example.com/ch1", images = 3, texts = 2)))
    }

    @Test
    fun `webtoon url does not collapse a text chapter`() {
        assertFalse(isManhwaLayout(chapter("http://webtoon.example.com/ch1", images = 0, texts = 40)))
    }

    @Test
    fun `webtoon url still wins for a short image-only chapter`() {
        assertTrue(isManhwaLayout(chapter("http://webtoon.example.com/ch1", images = 2, texts = 0)))
    }
}
