package io.aatricks.novelscraper.ui.components

import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.model.PrefetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterListSheetSelectionTest {

    @Test
    fun `unread selection keeps only chapters after current`() {
        val chapters = listOf(
            ChapterInfo(title = "Chapter 1", url = "url-1"),
            ChapterInfo(title = "Chapter 2", url = "url-2"),
            ChapterInfo(title = "Chapter 3", url = "url-3"),
            ChapterInfo(title = "Chapter 4", url = "url-4")
        )

        val selected = computeUnreadChapterSelection(
            allChapters = chapters,
            currentChapterUrl = "url-2",
            readUrls = emptySet(),
            downloadedUrls = emptySet()
        )

        assertEquals(listOf("url-3", "url-4"), selected)
    }

    @Test
    fun `unread selection excludes previous read and downloaded chapters`() {
        val chapters = listOf(
            ChapterInfo(title = "Chapter 1", url = "url-1"),
            ChapterInfo(title = "Chapter 2", url = "url-2"),
            ChapterInfo(title = "Chapter 3", url = "url-3"),
            ChapterInfo(title = "Chapter 4", url = "url-4"),
            ChapterInfo(title = "Chapter 5", url = "url-5")
        )

        val selected = computeUnreadChapterSelection(
            allChapters = chapters,
            currentChapterUrl = "url-2",
            readUrls = setOf("url-1", "url-4"),
            downloadedUrls = setOf("url-5")
        )

        assertEquals(listOf("url-3"), selected)
    }

    @Test
    fun `unread selection is empty when current chapter is missing`() {
        val chapters = listOf(
            ChapterInfo(title = "Chapter 1", url = "url-1"),
            ChapterInfo(title = "Chapter 2", url = "url-2")
        )

        val selected = computeUnreadChapterSelection(
            allChapters = chapters,
            currentChapterUrl = "missing",
            readUrls = emptySet(),
            downloadedUrls = emptySet()
        )

        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun `chapter cache status text shows partial cache progress`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 3,
                isComplete = false
            ),
            isInLibrary = true
        )

        assertEquals("Saved partially: 3/5 images", status)
    }

    @Test
    fun `chapter cache status text prefers saved locally for complete chapters`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 5,
                isComplete = true
            ),
            isInLibrary = true
        )

        assertEquals("Saved locally", status)
    }

    @Test
    fun `chapter cache status text is empty for undownloaded chapter`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = null,
            isInLibrary = false
        )

        assertNull(status)
    }
}
