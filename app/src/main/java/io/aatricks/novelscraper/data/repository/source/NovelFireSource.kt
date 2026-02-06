package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.ChapterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject

class NovelFireSource @Inject constructor(
    override val preferencesManager: PreferencesManager,
    override val okHttpClient: okhttp3.OkHttpClient
) : BaseJsoupSource(preferencesManager, okHttpClient) {
    override val name = "NovelFire"
    override val baseUrl = "https://novelfire.net"

    private fun cleanNovelTitle(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(Regex("^\\[\\d+\\]\\s*"), "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(Regex("^R\\s*\\d+(\\.\\d+)?\\s*"), "")
        // Remove Rank 123 at start
        clean = clean.replace(Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }
    
    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        val url = if (tags.isNotEmpty()) {
            val tag = tags.first()
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre-$tagSlug/sort-popular/status-all/all-novel?page=$page"
        } else {
            "$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page"
        }
        val document = getDocument(url)

        val items = mutableListOf<ExploreItem>()
        val bookLinks = document.select("a[href^='/book/']")

        bookLinks.forEach { link ->
            val rawTitle = link.text()
            val title = cleanNovelTitle(rawTitle)
            val href = link.attr("href")
            
            if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                 val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                 val img = parent?.select("img")?.first()
                 val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

                 val chapterText = parent?.select(".novel-stats, .stats, .chapters")?.text() ?: ""
                 val chapterCount = extractChapterCount(chapterText)

                 val absoluteUrl = resolveUrl(href)
                 if (items.none { it.url == absoluteUrl }) {
                     items.add(ExploreItem(
                         title = title,
                         url = absoluteUrl,
                         coverUrl = coverUrl.ifBlank { null },
                         source = name,
                         chapterCount = chapterCount
                     ))
                 }
            }
        }
        items
    }
    
    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/ajax/searchLive?inputContent=$encodedQuery"
        
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", baseUrl)
                .build()

            val response = okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            
            val json = JSONObject(response)
            val data = json.getJSONArray("data")
            val items = mutableListOf<ExploreItem>()
            
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val rawTitle = obj.getString("title")
                val title = cleanNovelTitle(rawTitle)
                val slug = obj.getString("slug")
                val image = obj.getString("image")
                
                items.add(ExploreItem(
                    title = title,
                    url = "$baseUrl/book/$slug",
                    coverUrl = resolveUrl(image),
                    source = name,
                    rank = obj.optInt("rank").toString(),
                    chapterCount = obj.optInt("total_chapter")
                ))
            }
            items
        }.getOrElse {
            val fallbackUrl = "$baseUrl/genre-all/sort-popular/status-all/all-novel?keyword=$encodedQuery&page=$page"
            val document = getDocument(fallbackUrl)

            val items = mutableListOf<ExploreItem>()
            val bookLinks = document.select("a[href^='/book/']")

            bookLinks.forEach { link ->
                val rawTitle = link.text()
                val title = cleanNovelTitle(rawTitle)
                val href = link.attr("href")

                 if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                     val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                     val img = parent?.select("img")?.first()
                     val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""
                     val absoluteUrl = resolveUrl(href)

                     val chapterText = parent?.select(".novel-stats, .stats, .chapters")?.text() ?: ""
                     val chapterCount = extractChapterCount(chapterText)

                     if (items.none { it.url == absoluteUrl }) {
                         items.add(ExploreItem(
                             title = title,
                             url = absoluteUrl,
                             coverUrl = coverUrl.ifBlank { null },
                             source = name,
                             chapterCount = chapterCount
                         ))
                     }
                 }
            }
            items
        }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = getDocument(url)
        val rawTitle = document.select("h1, .novel-title").first()?.text() ?: "Unknown Title"
        val title = cleanNovelTitle(rawTitle)
        val author = document.select(".author a, .author").first()?.text()
        val summary = extractSummary(document)

        val coverImg = document.select(".fixed-img .cover img, .book-cover img, .novel-cover img").first()
        val coverUrl = resolveUrl(coverImg?.findImage() ?: "")

        val infoText = document.text()
        val chapterCount = extractChapterCount(infoText)
        val rank = Regex("RANK\\s+(\\d+)", RegexOption.IGNORE_CASE).find(infoText)?.groupValues?.get(1)
        val rating = Regex("Average score is\\s+([0-9.]+)", RegexOption.IGNORE_CASE).find(infoText)?.groupValues?.get(1)

        val chaptersUrl = getChaptersUrl(url, document)
        val firstPageDoc = runCatching { getDocument(chaptersUrl) }.getOrDefault(document)

        val allChapters = mutableListOf<ChapterInfo>()
        allChapters.addAll(parseChapters(firstPageDoc))

        val maxPage = extractMaxPage(firstPageDoc, chaptersUrl)
        if (maxPage > 1) {
            allChapters.addAll(loadAdditionalChapterPages(chaptersUrl, maxPage))
        }

        val readingUrl = allChapters.firstOrNull()?.url ?: resolveUrl(document.select("a:contains(Read Now)").attr("href")).ifBlank { url }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl.ifBlank { null },
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            rank = rank,
            rating = rating,
            source = name,
            readingUrl = readingUrl,
            chapters = allChapters
        )
    }

    private fun extractSummary(document: org.jsoup.nodes.Document): String? {
        val summaryElement = document.select(".summary .content p, .summary .content, #summary, .description").first()
        return if (summaryElement != null) {
            document.select(".summary .content p").joinToString("\n\n") { it.text() }
                .ifEmpty { summaryElement.text() }
        } else null
    }

    private fun extractChapterCount(infoText: String): Int {
        return Regex("(\\d+)\\s*Chapters", RegexOption.IGNORE_CASE)
            .find(infoText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun getChaptersUrl(url: String, document: org.jsoup.nodes.Document): String {
        val chaptersPageHref = document.select("a[href$='/chapters']").attr("href")
        return if (chaptersPageHref.isNotBlank()) {
            resolveUrl(chaptersPageHref)
        } else {
            if (url.endsWith("/chapters")) url else "$url/chapters"
        }
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document): List<ChapterInfo> {
        return doc.select(".chapter-list li a, ul.chapters li a, .chapters li a").mapNotNull { element ->
            val chapterUrl = resolveUrl(element.attr("href"))
            if (chapterUrl.isBlank()) return@mapNotNull null

            var rawTitle = element.attr("title").ifBlank {
                element.select(".chapter-title").text().ifBlank { element.text() }
            }

            var cleanTitle = rawTitle.replace(Regex("\\d+\\s+(year|month|day|hour|minute|second)s?\\s+ago.*$"), "").trim()
            val leadingNumRegex = Regex("^(\\d+)\\s+(Chapter\\s+\\1.*)")
            leadingNumRegex.find(cleanTitle)?.let { match ->
                cleanTitle = match.groupValues[2]
            }

            ChapterInfo(title = cleanTitle, url = chapterUrl)
        }
    }

    private fun extractMaxPage(doc: org.jsoup.nodes.Document, chaptersUrl: String): Int {
        val paginationLinks = doc.select("ul.pagination .page-item .page-link")
        var maxPage = 1
        paginationLinks.forEach { link ->
            val pageNum = link.text().toIntOrNull()
            if (pageNum != null && pageNum > maxPage) {
                maxPage = pageNum
            } else {
                val hrefPage = link.attr("href").substringAfter("page=").toIntOrNull()
                if (hrefPage != null && hrefPage > maxPage) {
                    maxPage = hrefPage
                }
            }
        }
        return maxPage
    }

    private suspend fun loadAdditionalChapterPages(chaptersUrl: String, maxPage: Int): List<ChapterInfo> = kotlinx.coroutines.coroutineScope {
        (2..maxPage).map { page ->
            async {
                runCatching {
                    val pageUrl = if (chaptersUrl.contains("?")) "$chaptersUrl&page=$page" else "$chaptersUrl?page=$page"
                    parseChapters(getDocument(pageUrl))
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }


    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
        "Horror", "Josei", "Martial Arts", "Mature", "Mystery", "Psychological", "Romance", "School Life",
        "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xuanhuan"
    )
}
