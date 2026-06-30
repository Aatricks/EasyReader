package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.ChapterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    override val version = "1.0.0"

    companion object {
        private val BRACKET_NUMBER_REGEX = Regex("^\\[\\d+\\]\\s*")
        private val R_NUMBER_REGEX = Regex("^R\\s*\\d+(\\.\\d+)?\\s*")
        private val RANK_PREFIX_REGEX = Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE)
        private val RANK_REGEX = Regex("RANK\\s+(\\d+)", RegexOption.IGNORE_CASE)
        private val RATING_REGEX = Regex("Average score is\\s+([0-9.]+)", RegexOption.IGNORE_CASE)
        private val CHAPTERS_COUNT_REGEX = Regex("(\\d+)\\s*Chapters", RegexOption.IGNORE_CASE)
        private val TIME_AGO_REGEX = Regex("\\d+\\s+(year|month|day|hour|minute|second)s?\\s+ago.*$")
        private val LEADING_NUM_REGEX = Regex("^(\\d+)\\s+(Chapter\\s+\\1.*)")
    }

    private fun cleanNovelTitle(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(BRACKET_NUMBER_REGEX, "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(R_NUMBER_REGEX, "")
        // Remove Rank 123 at start
        clean = clean.replace(RANK_PREFIX_REGEX, "")
        return clean.trim()
    }
    
    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> =
        getNovels(BrowseMode.POPULAR, page, tags)

    override suspend fun getNovels(mode: BrowseMode, page: Int, tags: List<String>): List<ExploreItem> = io {
        val sortSlug = when (mode) {
            BrowseMode.POPULAR -> "sort-popular"
            BrowseMode.LATEST -> "sort-updated"
            BrowseMode.NEW -> "sort-new"
        }
        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val url = if (normalizedTags.isNotEmpty()) {
            val tag = normalizedTags.first()
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre-$tagSlug/$sortSlug/status-all/all-novel?page=$page"
        } else {
            "$baseUrl/genre-all/$sortSlug/status-all/all-novel?page=$page"
        }
        val document = getDocument(url)

        val items = mutableListOf<ExploreItem>()
        val addedUrls = mutableSetOf<String>()
        val bookLinks = document.select("a[href^='/book/']")

        bookLinks.forEach { link ->
            val rawTitle = link.text()
            val title = cleanNovelTitle(rawTitle)
            val href = link.attr("href")
            
            if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                 val absoluteUrl = resolveUrl(href)
                 if (addedUrls.add(absoluteUrl)) {
                     val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                     val img = parent?.select("img")?.first()
                     val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

                     val chapterText = parent?.select(".novel-stats, .stats, .chapters")?.text() ?: ""
                     val chapterCount = extractChapterCount(chapterText)

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
        // NovelFire's live-search endpoint reads the `keyword` query param; the old `inputContent`
        // name (now just the search box's element id) returns {"data":null}, which used to throw
        // and silently fall back to the popular page instead of searching.
        val url = "$baseUrl/ajax/searchLive?keyword=$encodedQuery"
        
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", baseUrl)
                .build()

            val response = okHttpClient.newCall(request).execute().use { it.body.string() }
            parseSearchJson(response)
        }.getOrElse {
            val fallbackUrl = "$baseUrl/genre-all/sort-popular/status-all/all-novel?keyword=$encodedQuery&page=$page"
            val document = getDocument(fallbackUrl)

            val items = mutableListOf<ExploreItem>()
            val addedUrls = mutableSetOf<String>()
            val bookLinks = document.select("a[href^='/book/']")

            bookLinks.forEach { link ->
                val rawTitle = link.text()
                val title = cleanNovelTitle(rawTitle)
                val href = link.attr("href")

                 if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                     val absoluteUrl = resolveUrl(href)

                     if (addedUrls.add(absoluteUrl)) {
                         val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                         val img = parent?.select("img")?.first()
                         val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

                         val chapterText = parent?.select(".novel-stats, .stats, .chapters")?.text() ?: ""
                         val chapterCount = extractChapterCount(chapterText)
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

    private fun parseSearchJson(response: String): List<ExploreItem> {
        val json = JSONObject(response)
        // A query with no hits returns {"data":null}; treat that as empty results rather than
        // throwing into the popular-page fallback.
        val data = json.optJSONArray("data") ?: JSONArray()
        val items = mutableListOf<ExploreItem>()
        val addedUrls = java.util.HashSet<String>()

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val title = cleanNovelTitle(obj.getString("title"))
            val bookUrl = "$baseUrl/book/${obj.getString("slug")}"

            if (addedUrls.add(bookUrl)) {
                items.add(ExploreItem(
                    title = title,
                    url = bookUrl,
                    coverUrl = resolveUrl(obj.getString("image")),
                    source = name,
                    rank = obj.optInt("rank").toString(),
                    chapterCount = obj.optInt("total_chapter")
                ))
            }
        }
        return items
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
        val rank = RANK_REGEX.find(infoText)?.groupValues?.get(1)
        val rating = RATING_REGEX.find(infoText)?.groupValues?.get(1)

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
        return CHAPTERS_COUNT_REGEX.find(infoText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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

            var cleanTitle = rawTitle.replace(TIME_AGO_REGEX, "").trim()
            LEADING_NUM_REGEX.find(cleanTitle)?.let { match ->
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
        val semaphore = Semaphore(3)
        (2..maxPage).map { page ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val pageUrl = if (chaptersUrl.contains("?")) "$chaptersUrl&page=$page" else "$chaptersUrl?page=$page"
                        parseChapters(getDocument(pageUrl))
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }


    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
        "Horror", "Josei", "Martial Arts", "Mature", "Mystery", "Psychological", "Romance", "School Life",
        "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xuanhuan"
    )
}
