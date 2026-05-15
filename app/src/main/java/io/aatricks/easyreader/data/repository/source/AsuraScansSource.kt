package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject

class AsuraScansSource @Inject constructor(
    override val preferencesManager: PreferencesManager,
    override val okHttpClient: okhttp3.OkHttpClient
) : BaseJsoupSource(preferencesManager, okHttpClient) {
    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val version = "1.0.0"

    companion object {
        private val CHAPTER_LABEL_REGEX = Regex("^(First|Latest)\\s+Chapter$", RegexOption.IGNORE_CASE)
    }

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        getDocument(buildBrowseUrl(page = page, tags = tags)).let { parseSeriesGrid(it) }
    }

    override suspend fun getNovels(mode: BrowseMode, page: Int, tags: List<String>): List<ExploreItem> = io {
        val order = when (mode) {
            BrowseMode.POPULAR -> "popular"
            BrowseMode.LATEST -> "update"
            BrowseMode.NEW -> "latest"
        }
        getDocument(buildBrowseUrl(page = page, tags = tags, order = order)).let { parseSeriesGrid(it) }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        getDocument(buildBrowseUrl(page = page, query = encoded)).let { parseSeriesGrid(it) }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = getDocument(url)

        val title = extractTitle(document)
        val coverUrl = extractCoverUrl(document)
        val summary = extractSummary(document)
        val author = extractAuthor(document)
        val chapters = extractChapters(document, url)

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl.ifBlank { null },
            author = author?.ifBlank { null },
            summary = summary?.ifBlank { null },
            chapterCount = chapters.size,
            source = name,
            readingUrl = chapters.firstOrNull()?.url,
            chapters = chapters
        )
    }

    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adaptation", "Adventure", "Comedy", "Crazy MC", "Cultivation", "Drama", "Fantasy",
        "Gore", "Harem", "Historical", "Horror", "Isekai", "Magic", "Martial Arts", "Mature",
        "Mecha", "Military", "Murim", "Mystery", "Necromancy", "Office Workers", "Post-Apocalyptic",
        "Psychological", "Reincarnation", "Returner", "Revenge", "Romance", "School Life", "Sci-Fi",
        "Seinen", "Shounen", "Slice of Life", "Sports", "Super Power", "Supernatural", "Survival",
        "System", "Thriller", "Time Travel", "Tower", "Tragedy", "Vampire", "Villainess", "Virtual Reality",
        "War", "Webtoon", "Zombies"
    )

    private fun buildBrowseUrl(
        page: Int,
        tags: List<String> = emptyList(),
        query: String? = null,
        order: String? = null
    ): String {
        val params = mutableListOf<String>()
        if (page > 1) params.add("page=$page")
        if (!query.isNullOrBlank()) params.add("q=$query")
        val genres = tags.map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(",") { it.lowercase().replace(" ", "-") }
        if (genres.isNotEmpty()) params.add("genres=$genres")
        if (!order.isNullOrBlank()) params.add("order=$order")
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "$baseUrl/browse$suffix"
    }

    private fun parseSeriesGrid(document: Document): List<ExploreItem> {
        return document.select(".series-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=\"/comics/\"]") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val absoluteUrl = resolveUrl(href)

            val title = card.selectFirst("h3")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val coverUrl = card.selectFirst("img")?.findImage()?.let { resolveUrl(it) }

            val chapterCount = card.selectFirst("span.bg-white\\/10")?.text()
                ?.let { io.aatricks.easyreader.util.TextUtils.extractChapterNumber(it)?.toInt() }
                ?: 0

            ExploreItem(
                title = title,
                url = absoluteUrl,
                coverUrl = coverUrl?.ifBlank { null },
                source = name,
                chapterCount = chapterCount
            )
        }.distinctBy { it.url }
    }

    private fun extractTitle(document: Document): String {
        val h1 = document.selectFirst("h1")?.text()?.trim()
        if (!h1.isNullOrBlank()) return h1
        val og = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        return og.removeSuffix(" | Asura Scans").trim()
    }

    private fun extractCoverUrl(document: Document): String {
        val og = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        if (og.isNotBlank()) return resolveUrl(og)
        val img = document.selectFirst("img[src*=\"/asura-images/covers/\"]")
        return resolveUrl(img?.findImage().orEmpty())
    }

    private fun extractSummary(document: Document): String? =
        document.metaContent(property = "og:description", name = "description")

    private fun extractAuthor(document: Document): String? {
        val byAuthor = document.selectFirst("a[href*=\"/browse?author=\"]")?.text()?.trim()
        if (!byAuthor.isNullOrBlank()) return byAuthor
        return document.selectFirst("a[href*=\"/browse?artist=\"]")?.text()?.trim()
    }

    private fun extractChapters(document: Document, seriesUrl: String): List<ChapterInfo> {
        val anchors = document.select("a[href*=\"/chapter/\"]")
        val byUrl = LinkedHashMap<String, ChapterInfo>()
        for (anchor in anchors) {
            val href = anchor.attr("href")
            if (href.isBlank()) continue
            val absoluteUrl = resolveUrl(href)
            if (!absoluteUrl.contains("/chapter/")) continue

            val title = buildChapterTitle(anchor) ?: continue

            val existing = byUrl[absoluteUrl]
            if (existing == null || existing.title.length < title.length) {
                byUrl[absoluteUrl] = ChapterInfo(title = title, url = absoluteUrl)
            }
        }
        return byUrl.values.sortedBy { extractChapterNumber(it.url) }
    }

    private fun buildChapterTitle(anchor: Element): String? {
        val label = anchor.selectFirst("span.font-medium")?.text()?.trim()
        val subtitle = anchor.selectFirst("span.truncate, span.text-white\\/50")?.text()?.trim().orEmpty()

        val base = when {
            !label.isNullOrBlank() && !CHAPTER_LABEL_REGEX.matches(label) -> label
            else -> anchor.text().trim().takeIf { it.isNotBlank() && !CHAPTER_LABEL_REGEX.matches(it) }
        } ?: return null

        return if (subtitle.isNotBlank() && !subtitle.equals(base, ignoreCase = true)) {
            "$base — $subtitle"
        } else {
            base
        }
    }

    private fun extractChapterNumber(url: String): Double {
        val numberPart = url.substringAfterLast("/chapter/").substringBefore("?").substringBefore("/")
        return numberPart.toDoubleOrNull() ?: Double.MAX_VALUE
    }
}
