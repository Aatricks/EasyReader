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
            // Try to find the title element - prioritize h3 a or specific title classes
            val titleElement = element.select("h3 a, .item-title, .story_name a").first() 
                ?: element.select("a").firstOrNull { it.text().isNotBlank() }
            
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            
            // Find the image - it might be in a different link than the title
            val img = element.select("img").first()
            var coverUrl = img?.attr("data-src")?.ifEmpty { 
                img?.attr("data-original")?.ifEmpty {
                    img?.attr("data-lazy-src")?.ifEmpty {
                        img?.attr("src")
                    }
                }
            } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl${if (href.startsWith("/")) "" else "/"}$href"
                if (coverUrl.isNotBlank()) {
                    if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
                    else if (!coverUrl.startsWith("http")) coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
                }
                items.add(ExploreItem(
                    title = title.trim(),
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
                    var coverUrl = img?.attr("data-src")?.ifEmpty { img?.attr("src") } ?: ""
                    
                    if (coverUrl.isNotBlank()) {
                        if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
                        else if (!coverUrl.startsWith("http")) coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
                    }

                    items.add(ExploreItem(
                        title = title,
                        url = absoluteUrl,
                        coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                        source = name
                    ))
                }
            }
        }
        
        items.distinctBy { it.url }
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
            // Try to find the title element - prioritize h3 a or specific title classes
            val titleElement = element.select("h3 a, .item-title, .story_name a").first() 
                ?: element.select("a").firstOrNull { it.text().isNotBlank() }
            
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            
            // Find the image - it might be in a different link than the title
            val img = element.select("img").first()
            var coverUrl = img?.attr("data-src")?.ifEmpty { 
                img?.attr("data-original")?.ifEmpty {
                    img?.attr("data-lazy-src")?.ifEmpty {
                        img?.attr("src")
                    }
                }
            } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl${if (href.startsWith("/")) "" else "/"}$href"
                if (coverUrl.isNotBlank()) {
                    if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
                    else if (!coverUrl.startsWith("http")) coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
                }
                items.add(ExploreItem(
                    title = title.trim(),
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
        val coverImg = document.select(".info-image img, .story-info-left img, .manga-info-pic img").first()
        var coverUrl = document.select("meta[property='og:image']").attr("content")
            .ifBlank { 
                coverImg?.attr("data-src")?.ifEmpty {
                    coverImg?.attr("data-original")?.ifEmpty {
                        coverImg?.attr("src")
                    }
                } ?: ""
            }
        
        if (coverUrl.isNotBlank()) {
            if (coverUrl.startsWith("//")) coverUrl = "https:$coverUrl"
            else if (!coverUrl.startsWith("http")) coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
        }

        val chapters = document.select(".chapter-name, .chapter-list a, .row a[href*='/chapter-']")
        val chapterCount = chapters.size
        
        val chapterList = chapters.map { element ->
            val chapterUrl = element.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }
            io.aatricks.novelscraper.data.model.ChapterInfo(
                title = element.text(),
                url = chapterUrl
            )
        }

        // First chapter is usually the last one in the list for mangabat
        val readingUrl = chapterList.lastOrNull()?.url

        ExploreItem(
            title = title,
            url = url,
            coverUrl = if (coverUrl.isBlank()) null else coverUrl,
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            source = name,
            readingUrl = readingUrl,
            chapters = chapterList
        )
    }
}