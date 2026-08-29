package io.aatricks.easyreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceInferenceTest {

    @Test
    fun `inferSourceNameFromUrl identifies known sources and smart scrape fallback`() {
        assertEquals("Novelight", inferSourceNameFromUrl("https://novelight.net/book/chapter/123"))
        assertEquals("NovelFire", inferSourceNameFromUrl("https://novelfire.net/book/martial-peak/chapter-1"))
        assertEquals("Asura Scans", inferSourceNameFromUrl("https://asurascans.com/comics/solo-leveling-chapter-1"))
        assertEquals("MangaBat", inferSourceNameFromUrl("https://www.mangabats.com/manga/manga-123/chapter-1"))
        assertEquals("Smart Scrape", inferSourceNameFromUrl("https://unknown-site.com/novel/chapter-1"))
        assertEquals("", inferSourceNameFromUrl("file:///storage/sample.epub"))
    }

    @Test
    fun `inferBaseNovelUrlFromUrl extracts canonical series URLs for known hosts`() {
        assertEquals(
            "https://novelfire.net/book/martial-peak",
            inferBaseNovelUrlFromUrl("https://novelfire.net/book/martial-peak/chapter-1")
        )
        assertEquals(
            "https://asurascans.com/comics/solo-leveling",
            inferBaseNovelUrlFromUrl("https://asurascans.com/comics/solo-leveling/chapter-1")
        )
        assertEquals(
            "https://www.mangabats.com/manga/manga-123",
            inferBaseNovelUrlFromUrl("https://www.mangabats.com/manga/manga-123/chapter-1")
        )
    }

    @Test
    fun `inferBaseNovelUrlFromUrl strips chapter segment for generic URLs`() {
        assertEquals(
            "https://generic-novel.com/series/overlord",
            inferBaseNovelUrlFromUrl("https://generic-novel.com/series/overlord/chapter-15")
        )
    }
}
