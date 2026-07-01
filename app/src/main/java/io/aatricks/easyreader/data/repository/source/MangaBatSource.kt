package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.ChapterInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject

class MangaBatSource @Inject constructor(
    override val preferencesManager: PreferencesManager,
    override val okHttpClient: okhttp3.OkHttpClient
) : BaseJsoupSource(preferencesManager, okHttpClient) {
    override val name = "MangaBat"
    override val baseUrl = "https://www.mangabats.com"
    override val version = "1.0.0"

    companion object {
        private val SUMMARY_REGEX = Regex(".*summary: ", RegexOption.IGNORE_CASE)
    }

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val url = if (normalizedTags.isNotEmpty()) {
            val tagSlug = normalizedTags.first().lowercase().replace(" ", "-")
            "$baseUrl/genre/$tagSlug?page=$page"
        } else {
            "$baseUrl/manga-list/hot-manga?page=$page"
        }
        val document = getDocument(url)
        val items = parseListElements(document)
        
        if (items.isEmpty()) {
            return@io parseFallbackHomepageLinks(document).distinctBy { it.url }
        }
        
        items.distinctBy { it.url }
    }

    private fun parseListElements(document: org.jsoup.nodes.Document): List<ExploreItem> {
        val elements = document.select(".list-comic-item-wrap, .list-story-item, .item-story, .story_item, .itemupdate")
        val seenUrls = mutableSetOf<String>()
        return elements.mapNotNull { element ->
            val anchor = anchorFor(element) ?: return@mapNotNull null

            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val absoluteUrl = resolveUrl(href)
            if (!seenUrls.add(absoluteUrl)) return@mapNotNull null

            val img = element.selectFirst("img") ?: anchor.selectFirst("img")
            val title = extractItemTitle(anchor, img) ?: return@mapNotNull null
            val coverUrl = img?.findImage()?.let { resolveUrl(it) }

            val chapterText = element.select(".list-story-item-wrap-chapter, .item-chapter a, .chapter").text()
            val chapterCount = io.aatricks.easyreader.util.TextUtils.extractChapterNumber(chapterText)?.toInt() ?: 0

            ExploreItem(
                title = title,
                url = absoluteUrl,
                coverUrl = coverUrl?.ifBlank { null },
                source = name,
                chapterCount = chapterCount
            )
        }
    }

    private fun anchorFor(element: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        if (element.tagName() == "a" && element.attr("href").isNotBlank()) return element
        return element.select("h3 a, .item-title, .story_name a, a[href*='/manga/']")
            .firstOrNull { it.attr("href").isNotBlank() }
            ?: element.selectFirst("a[href]")
    }

    private fun extractItemTitle(anchor: org.jsoup.nodes.Element, img: org.jsoup.nodes.Element?): String? {
        val candidates = listOf(
            anchor.text(),
            anchor.attr("title"),
            img?.attr("alt").orEmpty(),
            img?.attr("title").orEmpty()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun parseFallbackHomepageLinks(document: org.jsoup.nodes.Document): List<ExploreItem> {
        return document.select("a[href*='/manga/']").mapNotNull { link ->
            val href = link.attr("href")
            if (href.contains("/chapter", ignoreCase = true)) return@mapNotNull null

            val img = link.selectFirst("img")
                ?: link.parent()?.selectFirst("img")
                ?: link.closest("div")?.selectFirst("img")
            val title = extractItemTitle(link, img)?.takeIf { it.length > 5 } ?: return@mapNotNull null
            val coverUrl = img?.findImage()?.let { resolveUrl(it) }

            ExploreItem(
                title = title,
                url = resolveUrl(href),
                coverUrl = coverUrl?.ifBlank { null },
                source = name
            )
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encodedQuery = URLEncoder.encode(query.replace(" ", "_"), "UTF-8")
        val url = "$baseUrl/search/story/$encodedQuery?page=$page"
        val document = getDocument(url)
        parseListElements(document).distinctBy { it.url }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = getDocument(url)
        val title = document.select(".story-info-right h1, h1").text()
        
        val author = extractAuthor(document)
        val summary = document.select("#contentBox, .panel-story-info-description, .story-info-description")
            .first()?.text()?.replace("Description :", "")
            ?.replace(SUMMARY_REGEX, "")?.trim()
        
        val coverUrl = extractCoverUrl(document)
        
        // Chapters are loaded via API on www.mangabats.com
        val comicSlug = document.select("#chapter-list-container").attr("data-comic-slug").ifBlank {
            url.substringAfterLast("/").substringBefore("?")
        }
        
        val chapterList = fetchAllChaptersFromApi(comicSlug)

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl.ifBlank { null },
            author = author,
            summary = summary,
            chapterCount = chapterList.size,
            source = name,
            readingUrl = chapterList.firstOrNull()?.url,
            chapters = chapterList
        )
    }

    private suspend fun fetchAllChaptersFromApi(slug: String): List<ChapterInfo> = io {
        if (slug.isBlank()) return@io emptyList<ChapterInfo>()
        
        val limit = 50
        
        // Fetch first page to get initial data and check if there are more
        val (firstChapters, firstHasMore) = fetchChapterPage(slug, 0, limit)
        if (firstChapters.isEmpty() || !firstHasMore) {
            return@io firstChapters.reversed()
        }
        
        // Fetch remaining pages in parallel with semaphore
        val allChapters = firstChapters.toMutableList()
        val semaphore = Semaphore(3)
        var offset = limit
        
        while (true) {
            // Batch next 3 pages in parallel
            val offsets = (offset until offset + limit * 3 step limit).toList()
            val results = coroutineScope {
                offsets.map { pageOffset ->
                    async {
                        semaphore.withPermit {
                            fetchChapterPage(slug, pageOffset, limit)
                        }
                    }
                }.awaitAll()
            }
            
            var shouldStop = false
            for ((chapters, hasMore) in results) {
                if (chapters.isEmpty()) { shouldStop = true; break }
                allChapters.addAll(chapters)
                if (!hasMore) { shouldStop = true; break }
            }
            if (shouldStop) break
            offset += limit * 3
        }
        
        // API returns newest first, so we reverse to get oldest first (normal order)
        allChapters.reversed()
    }

    private fun fetchChapterPage(slug: String, offset: Int, limit: Int): Pair<List<ChapterInfo>, Boolean> {
        return runCatching {
            val apiUrl = "$baseUrl/api/manga/$slug/chapters?offset=$offset&limit=$limit"
            val response = okHttpClient.newCall(
                okhttp3.Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "$baseUrl/manga/$slug")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
            ).execute().use { it.body.string() }
            
            val json = JSONObject(response)
            if (!json.getBoolean("success")) return@runCatching Pair(emptyList<ChapterInfo>(), false)
            
            val dataObj = json.getJSONObject("data")
            val chaptersArray = dataObj.getJSONArray("chapters")
            val chapters = mutableListOf<ChapterInfo>()
            
            for (i in 0 until chaptersArray.length()) {
                val obj = chaptersArray.getJSONObject(i)
                val chapterSlug = obj.getString("chapter_slug")
                chapters.add(ChapterInfo(
                    title = obj.getString("chapter_name"),
                    url = "$baseUrl/manga/$slug/$chapterSlug"
                ))
            }
            
            val hasMore = dataObj.optJSONObject("pagination")?.optBoolean("has_more") ?: false
            Pair(chapters, hasMore)
        }.getOrDefault(Pair(emptyList(), false))
    }

    private fun extractAuthor(document: org.jsoup.nodes.Document): String {
        val authorByLink = document.select(".table-value a[href*='search/author'], .info-author a").text()
        if (authorByLink.isNotBlank()) return authorByLink
        
        val authorByLabel = document.select("li:contains(Author) :not(p)").text()
        if (authorByLabel.isNotBlank()) return authorByLabel
        
        return document.select("li:contains(Author)").text()
            .replace("Author(s) :", "")
            .replace("Author(s):", "").trim()
    }

    private fun extractCoverUrl(document: org.jsoup.nodes.Document): String {
        val ogImage = document.select("meta[property='og:image']").attr("content")
        if (ogImage.isNotBlank()) return resolveUrl(ogImage)
        
        val coverImg = document.select(".info-image img, .story-info-left img, .manga-info-pic img").first()
        return resolveUrl(coverImg?.findImage() ?: "")
    }


    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adult", "Adventure", "Comedy", "Cooking", "Doujinshi", "Drama", "Ecchi", "Fantasy", 
        "Gender bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Manhua", "Manhwa", 
        "Martial arts", "Mature", "Mecha", "Medical", "Mystery", "One shot", "Psychological", "Romance", 
        "School life", "Sci fi", "Seinen", "Shoujo", "Shoujo ai", "Shounen", "Shounen ai", "Slice of life", 
        "Smut", "Sports", "Supernatural", "Tragedy", "Webtoons", "Yaoi", "Yuri"
    )
}
