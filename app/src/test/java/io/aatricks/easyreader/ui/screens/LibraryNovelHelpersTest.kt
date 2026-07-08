package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.LibraryItem
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
    fun `buildDrawerNovelSections hides novels only when latest chapter progress reaches threshold`() {
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
                    id = "latest-nearly-done-1",
                    title = "Chapter 20",
                    baseTitle = "Latest Nearly Done",
                    currentChapter = "Chapter 20",
                    totalChapters = 20,
                    progress = 90,
                    lastRead = 375
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
    fun `buildDrawerNovelSections keeps recent novels when latest chapter cannot be determined`() {
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

    @Test
    fun `buildDrawerNovelSections hides finished novels even when resume item is older chapter`() {
        val sections = buildDrawerNovelSections(
            listOf(
                libraryItem(
                    id = "current-1",
                    title = "Chapter 2",
                    baseTitle = "Current Novel",
                    currentChapter = "Chapter 2",
                    totalChapters = 10,
                    progress = 20,
                    lastRead = 300,
                    isCurrentlyReading = true
                ),
                libraryItem(
                    id = "finished-old-1",
                    title = "Chapter 18",
                    baseTitle = "Finished But Opened Older",
                    currentChapter = "Chapter 18",
                    totalChapters = 20,
                    progress = 25,
                    lastRead = 250,
                    isCurrentlyReading = true
                ),
                libraryItem(
                    id = "finished-last-1",
                    title = "Chapter 20",
                    baseTitle = "Finished But Opened Older",
                    currentChapter = "Chapter 20",
                    totalChapters = 20,
                    progress = 100,
                    lastRead = 260
                ),
                libraryItem(
                    id = "unfinished-1",
                    title = "Chapter 11",
                    baseTitle = "Still Reading",
                    currentChapter = "Chapter 11",
                    totalChapters = 20,
                    progress = 100,
                    lastRead = 200
                )
            )
        )

        assertEquals(listOf("Still Reading"), sections.recentNovels.map { it.displayTitle })
    }

    @Test
    fun `buildDrawerNovelSections hides novels when highest stored chapter is nearly complete and no updates exist`() {
        val sections = buildDrawerNovelSections(
            listOf(
                libraryItem(
                    id = "current-1",
                    title = "Chapter 2",
                    baseTitle = "Current Novel",
                    currentChapter = "Chapter 2",
                    totalChapters = 10,
                    progress = 20,
                    lastRead = 300,
                    isCurrentlyReading = true
                ),
                libraryItem(
                    id = "mismatch-1",
                    title = "Chapter 1185",
                    baseTitle = "Count Mismatch Novel",
                    currentChapter = "Chapter 1185",
                    totalChapters = 1187,
                    progress = 99,
                    lastRead = 260
                ),
                libraryItem(
                    id = "mismatch-2",
                    title = "Chapter 1184",
                    baseTitle = "Count Mismatch Novel",
                    currentChapter = "Chapter 1184",
                    totalChapters = 1187,
                    progress = 100,
                    lastRead = 250
                ),
                libraryItem(
                    id = "unfinished-1",
                    title = "Chapter 11",
                    baseTitle = "Still Reading",
                    currentChapter = "Chapter 11",
                    totalChapters = 20,
                    progress = 100,
                    lastRead = 200
                )
            )
        )

        assertEquals(listOf("Still Reading"), sections.recentNovels.map { it.displayTitle })
    }

    @Test
    fun `isNovelFinished requires at least ninety percent progress on latest chapter`() {
        val almostDone = libraryItem(
            id = "almost-done",
            title = "Chapter 20",
            baseTitle = "Almost Done",
            currentChapter = "Chapter 20",
            totalChapters = 20,
            progress = 89
        )
        val doneEnough = almostDone.copy(id = "done-enough", progress = 90)

        assertTrue(!isNovelFinished(almostDone, latestKnownChapterCount = 20))
        assertTrue(isNovelFinished(doneEnough, latestKnownChapterCount = 20))
    }

    @Test
    fun `buildDrawerNovelSections hides novel when latest chapter item itself is at progress threshold`() {
        // Multi-item web novel: chapter 99 fully read, chapter 100 reached at 90% scroll.
        // Old items.any check failed because chapter-99 satisfied progress but not chapter-test;
        // new highest-chapter-item check evaluates chapter 100 directly.
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
                    id = "split-99",
                    title = "Chapter 99",
                    baseTitle = "Split Progress Novel",
                    currentChapter = "Chapter 99",
                    totalChapters = 100,
                    progress = 100,
                    lastRead = 400
                ),
                libraryItem(
                    id = "split-100",
                    title = "Chapter 100",
                    baseTitle = "Split Progress Novel",
                    currentChapter = "Chapter 100",
                    totalChapters = 100,
                    progress = 92,
                    lastRead = 410
                )
            )
        )

        assertTrue(sections.recentNovels.none { it.displayTitle == "Split Progress Novel" })
    }

    @Test
    fun `buildDrawerNovelSections keeps novel when latest chapter item is below progress threshold`() {
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
                    id = "split-99",
                    title = "Chapter 99",
                    baseTitle = "Mid Last Chapter Novel",
                    currentChapter = "Chapter 99",
                    totalChapters = 100,
                    progress = 100,
                    lastRead = 400
                ),
                libraryItem(
                    id = "split-100",
                    title = "Chapter 100",
                    baseTitle = "Mid Last Chapter Novel",
                    currentChapter = "Chapter 100",
                    totalChapters = 100,
                    progress = 50,
                    lastRead = 410
                )
            )
        )

        assertTrue(sections.recentNovels.any { it.displayTitle == "Mid Last Chapter Novel" })
    }

    @Test
    fun `buildDrawerNovelSections treats unfinished stale update flag as recent not update`() {
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
                    id = "stale-update",
                    title = "Chapter 10",
                    baseTitle = "Unfinished Stale Update",
                    currentChapter = "Chapter 10",
                    totalChapters = 11,
                    progress = 50,
                    lastRead = 450,
                    dateAdded = 450,
                    hasUpdates = true,
                    baseNovelUrl = "https://example.com/stale",
                    sourceName = "NovelFire"
                )
            )
        )

        assertTrue(sections.recentUpdates.none { it.displayTitle == "Unfinished Stale Update" })
        assertTrue(sections.recentNovels.any { it.displayTitle == "Unfinished Stale Update" })
    }

    @Test
    fun `buildDrawerNovelSections excludes update overflow from recent novels`() {
        val updateNovels = (1..5).map { index ->
            libraryItem(
                id = "update-$index",
                title = "Chapter 10",
                baseTitle = "Updated Novel $index",
                currentChapter = "Chapter 10",
                totalChapters = 11,
                progress = 100,
                lastRead = 400L + index,
                dateAdded = 400L + index,
                hasUpdates = true,
                baseNovelUrl = "https://example.com/update-$index",
                sourceName = "NovelFire"
            )
        }

        val sections = buildDrawerNovelSections(
            listOf(
                libraryItem(
                    id = "current-1",
                    title = "Chapter 5",
                    baseTitle = "Current Novel",
                    currentChapter = "Chapter 5",
                    totalChapters = 10,
                    progress = 40,
                    lastRead = 600,
                    isCurrentlyReading = true
                )
            ) + updateNovels
        )

        assertEquals(4, sections.recentUpdates.size)
        assertTrue(sections.recentNovels.none { it.displayTitle.startsWith("Updated Novel") })
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

    @Test
    fun `getLibraryItemResumeLabel returns correct labels for started and unstarted items`() {
        val neverRead = libraryItem(id = "1", title = "Chapter 0", baseTitle = "Novel", progress = 0, currentChapter = "Chapter 0")
        val neverReadStartedUrl = neverRead.copy(currentChapterUrl = "https://example.com/ch1")
        val startedRead = libraryItem(id = "2", title = "Chapter 2", baseTitle = "Novel", progress = 10, currentChapter = "Chapter 2")

        assertEquals("Start reading", getLibraryItemResumeLabel(neverRead))
        assertEquals("Resume Chapter 0", getLibraryItemResumeLabel(neverReadStartedUrl))
        assertEquals("Resume Chapter 2", getLibraryItemResumeLabel(startedRead))
    }
}
