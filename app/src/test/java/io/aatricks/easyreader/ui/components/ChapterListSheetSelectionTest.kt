package io.aatricks.easyreader.ui.components

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.PrefetchResult
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
    fun `chapter cache status hides incidental partial cache progress`() {
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

        assertEquals("In library", status)
    }

    @Test
    fun `chapter cache status hides incidental complete cache when not downloaded`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 5,
                isComplete = true
            ),
            isInLibrary = true,
            isDownloaded = false
        )

        assertEquals("In library", status)
    }

    @Test
    fun `chapter cache status shows downloaded when user-downloaded and cache complete`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 5,
                isComplete = true,
                isPersistentDownload = true
            ),
            isInLibrary = true,
            isDownloaded = true
        )

        assertEquals("Downloaded", status)
    }

    @Test
    fun `chapter cache status does not trust non-persistent complete cache as downloaded`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 5,
                isComplete = true,
                isPersistentDownload = false
            ),
            isInLibrary = true,
            isDownloaded = true
        )

        assertEquals("In library", status)
    }

    @Test
    fun `chapter cache status shows incomplete managed download`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 2,
                isComplete = false,
                isPersistentDownload = true
            ),
            isInLibrary = true,
            isDownloaded = true
        )

        assertEquals("Download incomplete: 2/5 images", status)
    }

    @Test
    fun `chapter cache status shows managed download in progress`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 2,
                isComplete = false,
                isInProgress = true,
                isPersistentDownload = true
            ),
            isInLibrary = true,
            isDownloaded = false
        )

        assertEquals("Downloading...", status)
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

    @Test
    fun `chapter cache status surfaces permanent failures as download incomplete not downloaded`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = PrefetchResult(
                url = "url-1",
                htmlCached = true,
                totalImages = 5,
                cachedImages = 3,
                isComplete = true,
                isPersistentDownload = true,
                hasPermanentFailures = true
            ),
            isInLibrary = true,
            isDownloaded = true
        )

        assertEquals("Download incomplete: 3/5 images", status)
    }

    @Test
    fun `chapter cache status verifies db remembered download when cache state is missing`() {
        val status = chapterCacheStatusText(
            isCurrent = false,
            cacheState = null,
            isInLibrary = true,
            isDownloaded = true
        )

        assertEquals("Verifying download...", status)
    }
}
