package io.aatricks.novelscraper.ui.screens

import io.aatricks.novelscraper.data.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryNovelHelpersTest {

    @Test
    fun `countDistinctNovelTitles counts novels instead of chapter rows`() {
        val items = listOf(
            libraryItem(id = "a-1", title = "Chapter 1", baseTitle = "Novel A"),
            libraryItem(id = "a-2", title = "Chapter 2", baseTitle = "Novel A"),
            libraryItem(id = "a-3", title = "Chapter 3", baseTitle = "Novel A"),
            libraryItem(id = "b-1", title = "Chapter 1", baseTitle = "Novel B"),
            libraryItem(id = "b-2", title = "Chapter 1", baseTitle = "Novel B", sourceName = "OtherSource")
        )

        assertEquals(3, countDistinctNovelTitles(items))
    }

    @Test
    fun `buildDrawerNovelSections hides only novels finished on latest chapter`() {
        val sections = buildDrawerNovelSections(
            listOf(
                libraryItem(
                    id = "current-1",
                    title = "Chapter 5",
                    baseTitle = "Current Novel",
                    currentChapter = "Chapter 5",
                    totalChapters = 10,
                    progress = 40,
                    lastRead = 500,
                    isCurrentlyReading = true
                ),
                libraryItem(
                    id = "finished-1",
                    title = "Chapter 20",
                    baseTitle = "Finished Novel",
                    currentChapter = "Chapter 20",
                    totalChapters = 20,
                    progress = 100,
                    lastRead = 450
                ),
                libraryItem(
                    id = "latest-incomplete-1",
                    title = "Chapter 20",
                    baseTitle = "Latest Incomplete",
                    currentChapter = "Chapter 20",
                    totalChapters = 20,
                    progress = 65,
                    lastRead = 400
                ),
                libraryItem(
                    id = "mid-read-1",
                    title = "Chapter 12",
                    baseTitle = "Mid Read Novel",
                    currentChapter = "Chapter 12",
                    totalChapters = 20,
                    progress = 100,
                    lastRead = 350
                ),
                libraryItem(
                    id = "update-1",
                    title = "Chapter 8",
                    baseTitle = "Updated Novel",
                    currentChapter = "Chapter 8",
                    totalChapters = 12,
                    progress = 100,
                    lastRead = 300,
                    dateAdded = 300,
                    hasUpdates = true,
                    baseNovelUrl = "https://example.com/updated",
                    sourceName = "NovelFire"
                ),
                libraryItem(
                    id = "update-2",
                    title = "Chapter 9",
                    baseTitle = "Updated Novel",
                    currentChapter = "Chapter 9",
                    totalChapters = 12,
                    progress = 100,
                    lastRead = 310,
                    dateAdded = 310,
                    hasUpdates = true,
                    baseNovelUrl = "https://example.com/updated",
                    sourceName = "NovelFire"
                )
            )
        )

        assertEquals("Current Novel", sections.continueNovel?.displayTitle)
        assertEquals(listOf("Updated Novel"), sections.recentUpdates.map { it.displayTitle })
        assertEquals(
            listOf("Latest Incomplete", "Mid Read Novel"),
            sections.recentNovels.map { it.displayTitle }
        )
    }

    @Test
    fun `buildDrawerNovelSections keeps recent novels when chapter progress is unknown`() {
        val sections = buildDrawerNovelSections(
            listOf(
                libraryItem(
                    id = "current-1",
                    title = "Chapter 3",
                    baseTitle = "Current Novel",
                    currentChapter = "Chapter 3",
                    totalChapters = 10,
                    progress = 20,
                    lastRead = 250,
                    isCurrentlyReading = true
                ),
                libraryItem(
                    id = "unknown-1",
                    title = "Interlude",
                    baseTitle = "Unknown Progress Novel",
                    currentChapter = "Interlude",
                    totalChapters = 18,
                    progress = 100,
                    lastRead = 200
                )
            )
        )

        assertEquals("Current Novel", sections.continueNovel?.displayTitle)
        assertEquals(listOf("Unknown Progress Novel"), sections.recentNovels.map { it.displayTitle })
        assertTrue(sections.recentUpdates.isEmpty())
    }

    private fun libraryItem(
        id: String,
        title: String,
        baseTitle: String,
        currentChapter: String = title,
        totalChapters: Int = 0,
        progress: Int = 0,
        lastRead: Long = 100,
        dateAdded: Long = lastRead,
        isCurrentlyReading: Boolean = false,
        hasUpdates: Boolean = false,
        baseNovelUrl: String = "",
        sourceName: String = ""
    ): LibraryItem {
        return LibraryItem(
            id = id,
            title = title,
            url = "https://example.com/$id",
            currentChapter = currentChapter,
            baseTitle = baseTitle,
            totalChapters = totalChapters,
            progress = progress,
            lastRead = lastRead,
            dateAdded = dateAdded,
            isCurrentlyReading = isCurrentlyReading,
            hasUpdates = hasUpdates,
            baseNovelUrl = baseNovelUrl,
            sourceName = sourceName
        )
    }
}
