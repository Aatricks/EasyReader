package io.aatricks.novelscraper.data.repository.source

import org.jsoup.Jsoup
import org.junit.Test
import org.junit.Assert.*
import io.aatricks.novelscraper.data.model.ExploreItem

class MangaBatSourceTest {

    @Test
    fun testParsePopularNovels() {
        val html = """
            <div class="item-story">
                <a class="item-title" href="https://www.mangabats.com/manga/manga-1">Manga 1</a>
                <img src="https://example.com/cover1.jpg">
            </div>
            <div class="item-story">
                <a class="item-title" href="/manga/manga-2">Manga 2</a>
                <img src="/cover2.jpg">
            </div>
        """.trimIndent()

        val document = Jsoup.parse(html)
        val items = mutableListOf<ExploreItem>()
        val baseUrl = "https://www.mangabats.com"
        val name = "MangaBat"

        val elements = document.select(".list-story-item, .item-story")
        elements.forEach { element ->
            val titleElement = element.select("h3 a, .item-title").first()
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            val coverUrl = element.select("img").attr("src")

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                items.add(ExploreItem(
                    title = title,
                    url = absoluteUrl,
                    coverUrl = coverUrl,
                    source = name
                ))
            }
        }

        assertEquals(2, items.size)
        assertEquals("Manga 1", items[0].title)
        assertEquals("https://www.mangabats.com/manga/manga-1", items[0].url)
        assertEquals("Manga 2", items[1].title)
        assertEquals("https://www.mangabats.com/manga/manga-2", items[1].url)
    }

    @Test
    fun testParseNovelDetails() {
        val html = """
            <div class="story-info-right">
                <h1>Mercenary Enrollment</h1>
            </div>
            <div class="info-author">
                <a href="#">Author Name</a>
            </div>
            <div class="panel-story-info-description">
                Description : This is a story about...
            </div>
            <div class="info-image">
                <img src="https://example.com/cover.jpg">
            </div>
            <div class="chapter-list">
                <a href="/manga/manga-1/chapter-1">Chapter 1</a>
                <a href="/manga/manga-1/chapter-2">Chapter 2</a>
            </div>
        """.trimIndent()

        val document = Jsoup.parse(html)
        val baseUrl = "https://www.mangabats.com"
        val name = "MangaBat"

        val title = document.select(".story-info-right h1, h1").text()
        val author = document.select(".table-value a[href*='search/author'], .info-author a").text()
        val summary = document.select(".panel-story-info-description, .story-info-description").text().replace("Description :", "").trim()
        val coverUrl = document.select(".info-image img, .story-info-left img").attr("src")
        
        val chapters = document.select(".chapter-name, .chapter-list a")
        val chapterCount = chapters.size
        val readingUrl = chapters.lastOrNull()?.attr("href")?.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        }

        assertEquals("Mercenary Enrollment", title)
        assertEquals("Author Name", author)
        assertEquals("This is a story about...", summary)
        assertEquals(2, chapterCount)
        assertEquals("https://www.mangabats.com/manga/manga-1/chapter-2", readingUrl)
    }
}
