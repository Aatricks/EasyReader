package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class MangaBatSource : NovelSource {
    override val name = "MangaBat"
    override val baseUrl = "https://h.mangabat.com"

    // Note: Due to anti-bot protection (Cloudflare, etc.), standard Jsoup connection might fail on some networks.
    // This implementation assumes standard HTML structure if accessible.

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/manga-list-all/$page"
        try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .timeout(10000)
                .get()

            val items = mutableListOf<ExploreItem>()
            val elements = document.select(".list-story-item")

            elements.forEach { element ->
                val titleElement = element.select(".item-title").first()
                val imgElement = element.select(".item-img").first()

                val title = titleElement?.text() ?: "Unknown"
                val href = titleElement?.attr("href") ?: ""
                val coverUrl = imgElement?.attr("src") ?: ""

                if (href.isNotBlank()) {
                    items.add(ExploreItem(
                        title = title,
                        url = href,
                        coverUrl = if (coverUrl.isNotBlank()) coverUrl else null,
                        source = name
                    ))
                }
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "_")
        val url = "$baseUrl/search/manga/$encodedQuery"

        try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .timeout(10000)
                .get()

            val items = mutableListOf<ExploreItem>()
            val elements = document.select(".list-story-item")

            elements.forEach { element ->
                val titleElement = element.select(".item-title").first()
                val imgElement = element.select(".item-img").first()

                val title = titleElement?.text() ?: "Unknown"
                val href = titleElement?.attr("href") ?: ""
                val coverUrl = imgElement?.attr("src") ?: ""

                if (href.isNotBlank()) {
                    items.add(ExploreItem(
                        title = title,
                        url = href,
                        coverUrl = if (coverUrl.isNotBlank()) coverUrl else null,
                        source = name
                    ))
                }
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", baseUrl)
            .timeout(10000)
            .get()

        val title = document.select(".story-info-right h1").text()
        val coverUrl = document.select(".story-info-left .info-image img").attr("src")
        val author = document.select(".variations-tableInfo tr:contains(Author) .table-value").text()
        val summary = document.select("#panel-story-info-description").text().removePrefix("Description :")

        // Count chapters
        val chapterList = document.select(".row-content-chapter li")
        val chapterCount = chapterList.size

        // Find reading URL (first chapter) - typically the last in the list (oldest) or first if sorted desc
        // Mangabat usually lists newest first.
        val firstChapterUrl = chapterList.lastOrNull()?.select("a")?.attr("href") ?: ""

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl,
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            source = name,
            readingUrl = firstChapterUrl
        )
    }
}
