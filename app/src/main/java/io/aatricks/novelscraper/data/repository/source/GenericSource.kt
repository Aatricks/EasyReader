package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL

/**
 * A generic source that attempts to discover content from a user-provided URL.
 * Uses heuristics to identify title, cover, summary, and chapter list.
 */
class GenericSource(override val baseUrl: String) : NovelSource {
    override val name = "Custom Source"

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> {
        // Generic source doesn't support "popular" listing from a base URL efficiently
        // without knowing the specific "popular" endpoint.
        // It could try to scrape the homepage.
        return scrapePageForLinks(baseUrl)
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> {
        // Generic search is hard without knowing the search parameter format.
        // Could try appending ?s=query or /search?q=query
        val searchUrls = listOf(
            "$baseUrl/?s=$query",
            "$baseUrl/search?q=$query",
            "$baseUrl/search?keyword=$query"
        )

        for (url in searchUrls) {
            try {
                val results = scrapePageForLinks(url)
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                // Continue trying
            }
        }
        return emptyList()
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        val title = document.title() ?: "Unknown Title"

        // Find largest image as likely cover
        val images = document.select("img")
        var coverUrl: String? = null
        var maxArea = 0

        images.forEach { img ->
            val w = img.attr("width").toIntOrNull() ?: 0
            val h = img.attr("height").toIntOrNull() ?: 0
            val src = img.attr("src")
            if (w * h > maxArea && src.isNotBlank()) {
                maxArea = w * h
                coverUrl = src
            }
        }

        if (coverUrl != null && !coverUrl!!.startsWith("http")) {
            val base = URL(baseUrl)
            coverUrl = if (coverUrl!!.startsWith("/")) "${base.protocol}://${base.host}$coverUrl" else "$baseUrl/$coverUrl"
        }

        // Summary heuristics: look for "description", "summary" in class or id, or long text block
        val summary = document.select(".description, #description, .summary, .content").text()

        // Find chapter links
        val links = document.select("a")
        val chapterLinks = links.filter {
            val text = it.text().lowercase()
            (text.contains("chapter") || text.contains("ch.")) && text.any { c -> c.isDigit() }
        }

        val chapterCount = chapterLinks.size

        // Find first chapter (heuristic: usually the one with lowest number or last in list)
        val firstChapterUrl = chapterLinks.firstOrNull()?.attr("href")?.let {
             if (it.startsWith("http")) it else {
                 val base = URL(baseUrl)
                 if (it.startsWith("/")) "${base.protocol}://${base.host}$it" else "$baseUrl/$it"
             }
        }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl,
            summary = summary,
            chapterCount = chapterCount,
            source = name,
            readingUrl = firstChapterUrl
        )
    }

    private suspend fun scrapePageForLinks(url: String): List<ExploreItem> = withContext(Dispatchers.IO) {
        try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()

            val items = mutableListOf<ExploreItem>()
            val links = document.select("a")

            links.forEach { link ->
                val href = link.attr("href")
                val title = link.text()

                // Heuristic: Link text length > 5 and contains no "chapter" (which would be a chapter link, not a book link)
                // and href is internal
                if (title.length > 5 && !title.lowercase().contains("chapter") && href.isNotBlank()) {
                     val fullUrl = if (href.startsWith("http")) href else {
                         val base = URL(baseUrl)
                         if (href.startsWith("/")) "${base.protocol}://${base.host}$href" else "$baseUrl/$href"
                     }

                     // Avoid adding the same page
                     if (fullUrl != url && items.none { it.url == fullUrl }) {
                         items.add(ExploreItem(
                             title = title,
                             url = fullUrl,
                             source = name
                         ))
                     }
                }
            }
            items.take(20) // Limit to 20 potential items
        } catch (e: Exception) {
            emptyList()
        }
    }
}
