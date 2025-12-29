package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class MangaDemonSource : NovelSource {
    override val name = "MangaDemon"
    override val baseUrl = "https://mangademon.com"

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/updates.php?page=$page" // Assuming updates page is a good proxy for content
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
            // Using generic selectors common for MangaDemon if specific ones aren't known
            // Often lists are in grid items
            val elements = document.select(".leftside .utb") // Based on common php structure or guess

            elements.forEach { element ->
                 val link = element.select("a").first()
                 val img = element.select("img").first()

                 val href = link?.attr("href") ?: ""
                 val title = link?.text() ?: img?.attr("alt") ?: "Unknown"
                 val coverUrl = img?.attr("src") ?: ""

                 if (href.isNotBlank() && items.none { it.url == href }) {
                     val fullUrl = if (href.startsWith("http")) href else "$baseUrl/$href"
                     items.add(ExploreItem(
                         title = title,
                         url = fullUrl,
                         coverUrl = if (coverUrl.startsWith("http")) coverUrl else "$baseUrl/$coverUrl",
                         source = name
                     ))
                 }
            }
            // Fallback if selectors fail (best effort)
            if (items.isEmpty()) {
                val links = document.select("a[href*='/manga/']")
                links.forEach { link ->
                     val href = link.attr("href")
                     val title = link.text()
                     if (title.isNotBlank()) {
                         items.add(ExploreItem(
                             title = title,
                             url = if (href.startsWith("http")) href else "$baseUrl$href",
                             source = name
                         ))
                     }
                }
            }

            items.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search.php?keyword=$encodedQuery"

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
            val elements = document.select("a[href*='/manga/']")

            elements.forEach { link ->
                val href = link.attr("href")
                val title = link.text()
                // Find nearest image
                val img = link.parent()?.select("img")?.first()
                    ?: link.parent()?.parent()?.select("img")?.first()
                val coverUrl = img?.attr("src") ?: ""

                if (title.isNotBlank() && !title.contains("Chapter", ignoreCase = true)) {
                     items.add(ExploreItem(
                         title = title,
                         url = if (href.startsWith("http")) href else "$baseUrl$href",
                         coverUrl = if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) "$baseUrl/$coverUrl" else coverUrl,
                         source = name
                     ))
                }
            }
            items.distinctBy { it.url }
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
            throw Exception("Cloudflare challenge detected")
        }

        val document = org.jsoup.Jsoup.parse(html, url)

        val title = document.select("h1").text()
        val coverUrl = document.select("img.rounded").attr("src") // Common class for cover
        val summary = document.select("#description, .description").text()

        // Find first chapter
        val chapterLinks = document.select("a[href*='chapter']")
        // Sort or find the "first" (usually last in list if desc, or look for Chapter 1)
        val firstChapterUrl = chapterLinks.lastOrNull()?.attr("href") ?: ""

        ExploreItem(
            title = title,
            url = url,
            coverUrl = if (coverUrl.startsWith("http")) coverUrl else "$baseUrl$coverUrl",
            summary = summary,
            source = name,
            readingUrl = if (firstChapterUrl.startsWith("http")) firstChapterUrl else "$baseUrl$firstChapterUrl"
        )
    }
}
