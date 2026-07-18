package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.ui.screens.library.LibraryFlattenState
import io.aatricks.easyreader.ui.screens.library.LibraryRenderItem
import io.aatricks.easyreader.ui.screens.library.flattenLibraryItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFlattenerTest {

    private fun createDummyItem(id: Long, novelTitle: String, chapterName: String): LibraryItem {
        return LibraryItem(
            id = id.toString(),
            title = novelTitle,
            url = "http://example.com/novel/$id",
            currentChapter = chapterName,
            currentChapterUrl = "http://example.com/novel/$id/ch",
            sourceName = "Source A",
            contentType = ContentType.WEB,
            baseNovelUrl = "http://example.com/novel",
            progress = 0,
            lastRead = id * 1000L,
            isDownloaded = false,
            isCurrentlyReading = false,
            chapterSummaries = emptyMap()
        )
    }

    @Test
    fun `collapsed novel series produces only source and novel header items`() {
        val items = (1L..10L).map { createDummyItem(it, "Test Novel", "Chapter $it") }
        val groupedBySource = mapOf("Source A" to mapOf("Test Novel" to items))

        val flattened = flattenLibraryItems(
            LibraryFlattenState(
                groupedBySource = groupedBySource,
                collapsedSources = emptySet(),
                expandedNovels = mapOf("Source A_Test Novel" to false),
                showFullChapters = emptyMap(),
                expandedSummaryChapterUrls = emptyMap()
            )
        )

        assertEquals(2, flattened.size)
        assertTrue(flattened[0] is LibraryRenderItem.SourceHeader)
        assertTrue(flattened[1] is LibraryRenderItem.NovelHeader)

        // Keys must be unique
        val keys = flattened.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `expanded first three chapters case flattens resume button, three rows, and show more control`() {
        val items = (1L..500L).map { createDummyItem(it, "Test Novel", "Chapter $it") }
        val groupedBySource = mapOf("Source A" to mapOf("Test Novel" to items))

        val flattened = flattenLibraryItems(
            LibraryFlattenState(
                groupedBySource = groupedBySource,
                collapsedSources = emptySet(),
                expandedNovels = mapOf("Source A_Test Novel" to true),
                showFullChapters = mapOf("Source A_Test Novel" to false),
                expandedSummaryChapterUrls = emptyMap()
            )
        )

        // SourceHeader, NovelHeader, NovelResumeButton, 3 ChapterRows, ShowMoreControl = 7 items
        assertEquals(7, flattened.size)
        assertTrue(flattened[0] is LibraryRenderItem.SourceHeader)
        assertTrue(flattened[1] is LibraryRenderItem.NovelHeader)
        assertTrue(flattened[2] is LibraryRenderItem.NovelResumeButton)
        assertTrue(flattened[3] is LibraryRenderItem.ChapterRow)
        assertTrue(flattened[4] is LibraryRenderItem.ChapterRow)
        assertTrue(flattened[5] is LibraryRenderItem.ChapterRow)
        assertTrue(flattened[6] is LibraryRenderItem.ShowMoreControl)

        val keys = flattened.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `expanded full 500 chapter series produces 500 individual chapter row items with unique keys`() {
        val items = (1L..500L).map { createDummyItem(it, "Test Novel", "Chapter $it") }
        val groupedBySource = mapOf("Source A" to mapOf("Test Novel" to items))

        val flattened = flattenLibraryItems(
            LibraryFlattenState(
                groupedBySource = groupedBySource,
                collapsedSources = emptySet(),
                expandedNovels = mapOf("Source A_Test Novel" to true),
                showFullChapters = mapOf("Source A_Test Novel" to true),
                expandedSummaryChapterUrls = emptyMap()
            )
        )

        // SourceHeader, NovelHeader, NovelResumeButton, 500 ChapterRows, ShowMoreControl = 504 items
        assertEquals(504, flattened.size)
        val chapterRows = flattened.filterIsInstance<LibraryRenderItem.ChapterRow>()
        assertEquals(500, chapterRows.size)

        val keys = flattened.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
