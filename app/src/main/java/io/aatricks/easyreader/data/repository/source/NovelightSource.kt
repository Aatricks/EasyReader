package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Novelight (novelight.net) — a Django + XHR web-novel site.
 *
 * Browse comes from the static homepage sections (`.block.popular`, `.block.recently`); the
 * catalog renders its cards via JS so it isn't scrapeable. Search, the chapter list, and the
 * chapter prose are all XHR endpoints that 403 without `X-Requested-With: XMLHttpRequest`, so
 * those go through [ajaxGet] rather than the plain [getDocument]. The chapter *content* itself
 * is fetched lazily by the reader — see [NovelightUrls] / WebContentLoader. Some titles and the
 * newest chapters are premium and 403/redirect; those degrade to an empty chapter list / unread
 * state rather than failing the whole add.
 */
class NovelightSource @Inject constructor(
    override val preferencesManager: PreferencesManager,
    override val okHttpClient: okhttp3.OkHttpClient
) : BaseJsoupSource(preferencesManager, okHttpClient) {
    override val name = "Novelight"
    override val baseUrl = NovelightUrls.BASE_URL
    override val version = "1.0.0"

    companion object {
        private const val CHAPTERS_PER_PAGE = 50.0
        private const val MAX_CHAPTER_PAGES = 200
        private const val CHAPTER_PAGE_CONCURRENCY = 3
        private val BOOK_ID_REGEX = Regex("""BOOK_ID\s*=\s*["'](\d+)["']""")
        private val CHAPTER_TITLE_REGEX =
            Regex("""^\s*(\d+(?:\.\d+)?)\s*chapter\b\s*[-:–—]?\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val META_SUMMARY_PREFIX = Regex("""^Read online\s+"[^"]*"\s*[-–—]\s*""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> =
        getNovels(BrowseMode.POPULAR, page, tags)

    override suspend fun getNovels(mode: BrowseMode, page: Int, tags: List<String>): List<ExploreItem> = io {
        // Browse is sourced from the static homepage (single page, no genre filtering). Bail out
        // for paged/tag-filtered requests so Novelight never injects unrelated popular titles into
        // someone else's tag intersection or page 2+.
        if (page > 1 || tags.any { it.isNotBlank() }) return@io emptyList()

        val document = getDocument("$baseUrl/")
        val section = if (mode == BrowseMode.LATEST) ".block.recently" else ".block.popular"
        parseListingCards(document, section).ifEmpty { parseListingCards(document, null) }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        if (page > 1) return@io emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = jsonField(ajaxGet("$baseUrl/ajax/search-live?search=$encoded"), "html")
        parseSearchHtml(html)
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = getDocument(url)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "Unknown Title" }
        val coverUrl = document.selectFirst(".poster img, .book-poster img, .image-cover img")
            ?.findImage()?.let { resolveUrl(it) }
        val author = document.selectFirst(".book-author a, .book-author, a[href*='/catalog/?authors=']")
            ?.text()?.trim()
        val summary = extractSummary(document)
        val genres = document.select(".tags.section a[href*='/catalog/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val chapters = extractBookId(document)?.let { loadAllChapters(it) }.orEmpty()
        val readingUrl = chapters.firstOrNull()?.url ?: url

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl?.ifBlank { null },
            author = author?.ifBlank { null },
            summary = summary,
            chapterCount = chapters.size,
            source = name,
            readingUrl = readingUrl,
            chapters = chapters,
            genres = genres
        )
    }

    override suspend fun getTags(): List<String> = emptyList()

    // --- Parsing (pure, unit-testable without a network) -------------------------------------

    internal fun parseListingCards(document: Document, sectionSelector: String?): List<ExploreItem> {
        val scope = if (sectionSelector != null) document.selectFirst(sectionSelector) else document
        scope ?: return emptyList()
        return scope.select("a.manga-item[href*='/book/']").mapNotNull { anchor ->
            val url = resolveUrl(anchor.attr("href"))
            if (url.isBlank()) return@mapNotNull null
            val title = anchor.selectFirst(".title")?.text()?.trim()
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null
            val cover = anchor.selectFirst(".poster img, img")?.findImage()?.let { resolveUrl(it) }
            ExploreItem(title = title, url = url, coverUrl = cover?.ifBlank { null }, source = name)
        }.distinctBy { it.url }
    }

    internal fun parseSearchHtml(html: String): List<ExploreItem> {
        val document = Jsoup.parse(html, baseUrl)
        val anchors = document.select("#ln-search-results a.manga-list-item[href*='/book/']")
            .ifEmpty { document.select("a.manga-list-item[href*='/book/']") }
        return anchors.mapNotNull { anchor ->
            val url = resolveUrl(anchor.attr("href"))
            if (url.isBlank()) return@mapNotNull null
            val title = anchor.selectFirst(".manga-list__info .title, .title")?.text()?.trim()
                ?: anchor.attr("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = anchor.selectFirst(".image img, img")?.findImage()?.let { resolveUrl(it) }
            ExploreItem(title = title, url = url, coverUrl = cover?.ifBlank { null }, source = name)
        }.distinctBy { it.url }
    }

    internal fun parseChapterPaginationHtml(html: String): List<ChapterInfo> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("a[href*='/book/chapter/']").mapNotNull { anchor ->
            val url = resolveUrl(anchor.attr("href"))
            if (url.isBlank()) return@mapNotNull null
            val rawTitle = (anchor.selectFirst(".title")?.text()?.trim().orEmpty())
                .ifBlank { anchor.text().trim() }
            val match = CHAPTER_TITLE_REGEX.find(rawTitle)
            val number = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val name = match?.groupValues?.getOrNull(2)?.trim().orEmpty()
            val title = when {
                number != null -> {
                    val label = "Chapter ${formatChapterNumber(number)}"
                    if (name.isNotBlank()) "$label - $name" else label
                }
                rawTitle.isNotBlank() -> rawTitle
                else -> url
            }
            ChapterInfo(title = title, url = url, number = number)
        }.distinctBy { it.url }
    }

    internal fun extractBookId(document: Document): String? =
        BOOK_ID_REGEX.find(document.html())?.groupValues?.getOrNull(1)

    private fun extractSummary(document: Document): String? =
        document.metaContent(name = "description")
            ?.replace(META_SUMMARY_PREFIX, "")
            ?.trim()
            ?.ifBlank { null }

    private fun formatChapterNumber(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    // --- Chapter list paging -----------------------------------------------------------------

    private suspend fun loadAllChapters(bookId: String): List<ChapterInfo> = coroutineScope {
        val firstPage = parseChapterPaginationHtml(fetchChapterPageHtml(bookId, 1))
        if (firstPage.isEmpty()) return@coroutineScope emptyList()

        // The pagination JSON exposes no page count, but page 1 holds the newest (highest-numbered)
        // chapters, so the max chapter number estimates the page count. We parallel-fetch up to the
        // estimate, then sequentially extend in case numbering is sparse and undercounts.
        val maxNumber = firstPage.maxOfOrNull { it.number ?: 0.0 } ?: 0.0
        val estimatedPages = ((maxNumber / CHAPTERS_PER_PAGE).toInt() + 1).coerceIn(1, MAX_CHAPTER_PAGES)

        val all = ArrayList(firstPage)
        if (estimatedPages > 1) {
            val semaphore = Semaphore(CHAPTER_PAGE_CONCURRENCY)
            val rest = (2..estimatedPages).map { page ->
                async {
                    semaphore.withPermit {
                        runCatching { parseChapterPaginationHtml(fetchChapterPageHtml(bookId, page)) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
            all.addAll(rest)
        }

        var page = estimatedPages + 1
        while (page <= MAX_CHAPTER_PAGES) {
            val more = runCatching { parseChapterPaginationHtml(fetchChapterPageHtml(bookId, page)) }
                .getOrDefault(emptyList())
            if (more.isEmpty()) break
            all.addAll(more)
            page++
        }

        all.distinctBy { it.url }.sortedBy { it.number ?: Double.MAX_VALUE }
    }

    private fun fetchChapterPageHtml(bookId: String, page: Int): String =
        jsonField(ajaxGet("$baseUrl/book/ajax/chapter-pagination?book_id=$bookId&page=$page"), "html")

    // --- HTTP --------------------------------------------------------------------------------

    private fun jsonField(body: String, field: String): String =
        runCatching { JSONObject(body).optString(field) }.getOrDefault("")

    private fun ajaxGet(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
            return response.body.string()
        }
    }
}
