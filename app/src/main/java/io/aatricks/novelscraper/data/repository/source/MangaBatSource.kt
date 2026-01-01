package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class MangaBatSource : NovelSource {
    override val name = "MangaBat"
    override val baseUrl = "https://www.mangabats.com"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = if (page == 1) baseUrl else "$baseUrl/manga-list-all/$page"
        val document = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        // Add .itemupdate for the main page style list
        val elements = document.select(".list-story-item, .item-story, .story_item, .itemupdate")

        elements.forEach { element ->
            val titleElement = element.select("h3 a, .item-title, .story_name a, .tooltip").first()
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            val img = element.select("img").first()
            var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) coverUrl = "$baseUrl$coverUrl"
                items.add(ExploreItem(
                    title = title,
                    url = absoluteUrl,
                    coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                    source = name
                ))
            }
        }
        
        // Generic fallback for homepage (unchanged)
        if (items.isEmpty()) {
            document.select("a[href*='/manga/']").forEach { link ->
                val title = link.text().trim()
                val href = link.attr("href")
                if (title.length > 5 && !title.contains("Chapter", ignoreCase = true) && items.none { it.url.contains(href) }) {
                    val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                    val img = link.parent()?.select("img")?.first() ?: link.closest("div")?.select("img")?.first()
                    items.add(ExploreItem(
                        title = title,
                        url = absoluteUrl,
                        coverUrl = img?.attr("src"),
                        source = name
                    ))
                }
            }
        }
        
        items.distinctBy { it.url }.take(24)
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query.replace(" ", "_"), "UTF-8")
        // Correct search URL for Mangabat is /search/story/
        val url = "$baseUrl/search/story/$encodedQuery?page=$page"
        
        val document = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        val elements = document.select(".list-story-item, .item-story, .story_item, .itemupdate")

        elements.forEach { element ->
            val titleElement = element.select("h3 a, .item-title, .story_name a, .tooltip").first()
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            val img = element.select("img").first()
            var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) coverUrl = "$baseUrl$coverUrl"
                items.add(ExploreItem(
                    title = title,
                    url = absoluteUrl,
                    coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                    source = name
                ))
            }
        }
        items.distinctBy { it.url }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(url) // Important for details
            .timeout(10000)
            .get()

        val title = document.select(".story-info-right h1, h1").text()
        
        // Robust author scraping
        var author = document.select(".table-value a[href*='search/author'], .info-author a").text()
        if (author.isBlank()) {
            author = document.select("li:contains(Author) :not(p)").text()
                .ifBlank { document.select("li:contains(Author)").text().replace("Author(s) :", "").replace("Author(s):", "").trim() }
        }
        
        // Improved summary selector
        val summaryElement = document.select("#contentBox, .panel-story-info-description, .story-info-description").first()
        val summary = summaryElement?.text()?.replace("Description :", "")?.replace(Regex(".*summary: ", RegexOption.IGNORE_CASE), "")?.trim()
        
        // Improved coverUrl selector using OpenGraph or specific image alt
        var coverUrl = document.select("meta[property='og:image']").attr("content")
            .ifBlank { document.select(".info-image img, .story-info-left img, .manga-info-pic img").attr("src") }
        
        if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) coverUrl = "$baseUrl$coverUrl"

        val chapters = document.select(".chapter-name, .chapter-list a, .row a[href*='/chapter-']")
        val chapterCount = chapters.size
        // First chapter is usually the last one in the list for mangabat
        val readingUrl = chapters.lastOrNull()?.attr("href")?.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = if (coverUrl.isBlank()) null else coverUrl,
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            source = name,
            readingUrl = readingUrl
        )
    }
}
