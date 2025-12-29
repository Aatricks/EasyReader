package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class MangaBatSource : NovelSource {
    override val name = "MangaBat"
    override val baseUrl = "https://readmangabat.com" // Or https://h.mangabat.com depending on region

    // Note: Due to anti-bot protection (Cloudflare, etc.), standard Jsoup connection might fail on some networks.
    // This implementation assumes standard HTML structure if accessible.

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = "https://m.mangabat.com/manga-list-all/$page"
        try {
            val requestBuilder = okhttp3.Request.Builder().url(url)
            io.aatricks.novelscraper.util.NetworkUtils.getHeaders().forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }
            
            val response = io.aatricks.novelscraper.util.NetworkUtils.okHttpClient.newCall(requestBuilder.build()).execute()
            val html = response.body?.string() ?: ""
            
            if (io.aatricks.novelscraper.util.NetworkUtils.isCloudflareChallenge(html)) {
                // We don't have a direct way to trigger UI from here easily without changing interface, 
                // but usually the first request that fails will be caught by ContentRepository
                return@withContext emptyList()
            }

            val document = org.jsoup.Jsoup.parse(html, url)
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
        val url = "https://m.mangabat.com/search/manga/$encodedQuery"

        try {
            val requestBuilder = okhttp3.Request.Builder().url(url)
            io.aatricks.novelscraper.util.NetworkUtils.getHeaders().forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }
            
            val response = io.aatricks.novelscraper.util.NetworkUtils.okHttpClient.newCall(requestBuilder.build()).execute()
            val html = response.body?.string() ?: ""
            
            if (io.aatricks.novelscraper.util.NetworkUtils.isCloudflareChallenge(html)) {
                return@withContext emptyList()
            }

            val document = org.jsoup.Jsoup.parse(html, url)
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
        val requestBuilder = okhttp3.Request.Builder().url(url)
        io.aatricks.novelscraper.util.NetworkUtils.getHeaders().forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }
        
        val response = io.aatricks.novelscraper.util.NetworkUtils.okHttpClient.newCall(requestBuilder.build()).execute()
        val html = response.body?.string() ?: ""
        
        if (io.aatricks.novelscraper.util.NetworkUtils.isCloudflareChallenge(html)) {
            // This is problematic because we can't return details if challenged here.
            // But if it's already in library, ReaderViewModel will handle it.
            throw Exception("Cloudflare challenge detected")
        }

        val document = org.jsoup.Jsoup.parse(html, url)

        val title = document.select(".story-info-right h1").text()
        val coverUrl = document.select(".story-info-left .info-image img").attr("src")
        val author = document.select(".variations-tableInfo tr:contains(Author) .table-value").text()
        val summary = document.select("#panel-story-info-description").text().removePrefix("Description :")

        val chapterCountText = document.select(".story-info-right .variations-tableInfo tr:contains(Status) .table-value").text()
        // Approximate parsing or just count the list
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
