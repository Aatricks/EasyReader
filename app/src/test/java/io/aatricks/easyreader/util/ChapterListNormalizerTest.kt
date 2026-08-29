package io.aatricks.easyreader.util

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterListNormalizerTest {

    @Test
    fun `normalizeChapterList removes duplicate and blank urls while preserving order`() {
        val chapters = listOf(
            ChapterInfo(title = "Chapter 1", url = " https://example.com/ch-1 "),
            ChapterInfo(title = "Chapter 1 duplicate", url = "https://example.com/ch-1"),
            ChapterInfo(title = "Blank", url = "   "),
            ChapterInfo(title = "Chapter 2", url = "https://example.com/ch-2")
        )

        val normalized = normalizeChapterList(chapters)

        assertEquals(
            listOf("https://example.com/ch-1", "https://example.com/ch-2"),
            normalized.map { it.url }
        )
        assertEquals(listOf("Chapter 1", "Chapter 2"), normalized.map { it.title })
    }

    @Test
    fun `normalizeExploreItemDetails uses normalized chapters for count and reading url`() {
        val details = ExploreItem(
            title = "Novel",
            url = "https://example.com/novel",
            chapterCount = 9,
            source = "NovelFire",
            readingUrl = "   ",
            chapters = listOf(
                ChapterInfo(title = "Chapter 1", url = "https://example.com/ch-1"),
                ChapterInfo(title = "Chapter 1 duplicate", url = "https://example.com/ch-1"),
                ChapterInfo(title = "Chapter 2", url = "https://example.com/ch-2")
            )
        )

        val normalized = normalizeExploreItemDetails(details)

        assertEquals(2, normalized.chapterCount)
        assertEquals(
            listOf("https://example.com/ch-1", "https://example.com/ch-2"),
            normalized.chapters.map { it.url }
        )
        assertEquals("https://example.com/ch-1", normalized.readingUrl)
    }

    @Test
    fun `normalizeExploreItemDetails keeps existing metadata when no valid chapters remain`() {
        val details = ExploreItem(
            title = "Novel",
            url = "https://example.com/novel",
            chapterCount = 42,
            source = "NovelFire",
            readingUrl = null,
            chapters = listOf(ChapterInfo(title = "Broken", url = " "))
        )

        val normalized = normalizeExploreItemDetails(details)

        assertEquals(42, normalized.chapterCount)
        assertTrue(normalized.chapters.isEmpty())
        assertNull(normalized.readingUrl)
    }

    // Novelight reading URLs are /book/chapter/{id}; the id is not the chapter number, so a
    // URL-derived label ("Chapter 141313") must be corrected to the chapter list's real label.
    private val novelightList = listOf(
        ChapterInfo(title = "Chapter 102 - The Duel", url = "https://novelight.net/book/chapter/141313", number = 102.0),
        ChapterInfo(title = "Chapter 103 - Aftermath", url = "https://novelight.net/book/chapter/141320", number = 103.0)
    )

    @Test
    fun `resolveChapterLabelFromList corrects a wrong url-derived number`() {
        val resolved = resolveChapterLabelFromList(
            url = "https://novelight.net/book/chapter/141313",
            currentLabel = "Chapter 141313",
            chapters = novelightList
        )
        assertEquals("Chapter 102 - The Duel", resolved)
    }

    @Test
    fun `resolveChapterLabelFromList is a no-op when the label already has the right number`() {
        // Sources whose URL encodes the real number already show it — don't override their label.
        val resolved = resolveChapterLabelFromList(
            url = "https://novelight.net/book/chapter/141313",
            currentLabel = "Chapter 102: The Duel (translator note)",
            chapters = novelightList
        )
        assertNull(resolved)
    }

    @Test
    fun `resolveChapterLabelFromList returns null when the url is not in the list`() {
        val resolved = resolveChapterLabelFromList(
            url = "https://novelight.net/book/chapter/999999",
            currentLabel = "Chapter 999999",
            chapters = novelightList
        )
        assertNull(resolved)
    }

    @Test
    fun `resolveChapterLabelFromList returns null when the list entry has no number`() {
        val list = listOf(ChapterInfo(title = "Epilogue", url = "https://x/c/1", number = null))
        val resolved = resolveChapterLabelFromList(
            url = "https://x/c/1",
            currentLabel = "Chapter 1",
            chapters = list
        )
        assertNull(resolved)
    }

    @Test
    fun `healCurrentChapterLabel swaps the id for the real number and keeps the prefix`() {
        val healed = healCurrentChapterLabel(
            currentChapter = "Chapter 141313",
            currentUrl = "https://novelight.net/book/chapter/141313",
            chapters = novelightList
        )
        assertEquals("Chapter 102", healed)
    }

    @Test
    fun `healCurrentChapterLabel handles a bare number label`() {
        val healed = healCurrentChapterLabel(
            currentChapter = "141313",
            currentUrl = "https://novelight.net/book/chapter/141313",
            chapters = novelightList
        )
        assertEquals("102", healed)
    }

    @Test
    fun `healCurrentChapterLabel fills in a label with no number`() {
        val healed = healCurrentChapterLabel(
            currentChapter = "",
            currentUrl = "https://novelight.net/book/chapter/141313",
            chapters = novelightList
        )
        assertEquals("Chapter 102", healed)
    }

    @Test
    fun `healCurrentChapterLabel is a no-op when the number is already correct`() {
        val healed = healCurrentChapterLabel(
            currentChapter = "Chapter 102",
            currentUrl = "https://novelight.net/book/chapter/141313",
            chapters = novelightList
        )
        assertNull(healed)
    }

    @Test
    fun `healCurrentChapterLabel is a no-op when the url is not in the list`() {
        val healed = healCurrentChapterLabel(
            currentChapter = "Chapter 5",
            currentUrl = "https://novelight.net/book/chapter/000000",
            chapters = novelightList
        )
        assertNull(healed)
    }

    @Test
    fun `normalizeChapterUrl strips www and trailing slashes and unifies scheme`() {
        val normalized = normalizeChapterUrl("http://www.example.com/novel/chapter-1/")
        assertEquals("http://example.com/novel/chapter-1", normalized)
    }

    @Test
    fun `areChapterUrlsMatching matches equivalent URLs across schemes and www`() {
        val url1 = "https://www.example.com/novel/chapter-1/"
        val url2 = "http://example.com/novel/chapter-1"
        assertTrue(areChapterUrlsMatching(url1, url2))
    }

    @Test
    fun `matchChapterIndex falls back to chapter number when URLs differ`() {
        val chapters = listOf(
            ChapterInfo(title = "Chapter 1 - Intro", url = "https://source.com/c1", number = 1.0),
            ChapterInfo(title = "Chapter 2 - Next", url = "https://source.com/c2", number = 2.0)
        )
        val matchedIndex = matchChapterIndex(
            chapters = chapters,
            targetUrl = "https://other-source.com/read/c2",
            targetTitle = "Chapter 2 - Next"
        )
        assertEquals(1, matchedIndex)
    }
}
