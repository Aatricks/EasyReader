package io.aatricks.novelscraper.data.repository.source

import org.jsoup.Jsoup
import org.junit.Test
import org.junit.Assert.*
import io.aatricks.novelscraper.data.model.ExploreItem

class NovelFireSourceTest {

    @Test
    fun testParsePopularNovels() {
        val html = """
            <div class="book-item">
                <a href="/book/novel-1">Novel 1</a>
                <img src="/cover1.jpg">
            </div>
            <div class="item">
                <a href="/book/novel-2">Novel 2</a>
                <img src="https://example.com/cover2.jpg">
            </div>
        """.trimIndent()

        val document = Jsoup.parse(html)
        val items = mutableListOf<ExploreItem>()
        val baseUrl = "https://novelfire.net"
        val name = "NovelFire"

        val bookLinks = document.select("a[href^='/book/']")
        bookLinks.forEach {
            val title = it.text()
            val href = it.attr("href")
            if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                 val parent = it.closest(".novel-item, .item, .book-item") ?: it.parent()?.parent()
                 val img = parent?.select("img")?.first()
                 var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                 if (coverUrl.startsWith("/")) coverUrl = "$baseUrl$coverUrl"

                 if (items.none { item -> item.url == "$baseUrl$href" }) {
                     items.add(ExploreItem(
                         title = title,
                         url = "$baseUrl$href",
                         coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                         source = name
                     ))
                 }
            }
        }

        assertEquals(2, items.size)
        assertEquals("Novel 1", items[0].title)
        assertEquals("Novel 2", items[1].title)
    }

    @Test
    fun testParseNovelDetails() {
        val html = """
            <h1>Shadow Slave</h1>
            <div class="author">GuiltyThree</div>
            <div class="summary">
                <div class="content">
                    <p>Sunless, a young man who...</p>
                    <p>He was born in...</p>
                </div>
            </div>
            <div class="fixed-img">
                <div class="cover">
                    <img src="/cover.jpg">
                </div>
            </div>
            <div class="chapter-list">
                <a href="/book/shadow-slave/chapter-1">Read Now</a>
            </div>
        """.trimIndent()

        val document = Jsoup.parse(html)
        val baseUrl = "https://novelfire.net"

        val title = document.select("h1, .novel-title").first()?.text() ?: "Unknown Title"
        val author = document.select(".author a, .author").first()?.text()
        val summaryElement = document.select(".summary .content p, .summary .content, #summary, .description").first()
        val summary = if (summaryElement != null) {
            document.select(".summary .content p").joinToString("\n\n") { it.text() }
                .ifEmpty { summaryElement.text() }
        } else null
        
        var coverUrl = document.select(".fixed-img .cover img, .book-cover img, .novel-cover img").attr("src")
        if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) {
            coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
        }

        val readNowHref = document.select("a:contains(Read Now)").attr("href")

        assertEquals("Shadow Slave", title)
        assertEquals("GuiltyThree", author)
        assertTrue(summary?.contains("Sunless") == true)
        assertEquals("https://novelfire.net/cover.jpg", coverUrl)
        assertEquals("/book/shadow-slave/chapter-1", readNowHref)
    }
}
