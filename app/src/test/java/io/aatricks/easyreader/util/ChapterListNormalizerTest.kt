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
}
