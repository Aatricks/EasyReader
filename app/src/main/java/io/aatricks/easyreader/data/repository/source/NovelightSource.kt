package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.util.HttpRetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
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
        private const val CHAPTERS_PER_PAGE = 50
        private const val MAX_CHAPTER_PAGES = 200
        private const val CHAPTER_PAGE_CONCURRENCY = 2
        private const val MAX_PAGE_RETRIES = 3
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_REDIRECT_MIN = 300
        private const val HTTP_REDIRECT_MAX = 399
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

    /**
     * The pagination endpoint exposes no page count, and out-of-range pages *clamp* to the last
     * page's content instead of returning empty. So we estimate the page count from page 1 (the
     * newest, highest-numbered chapters, ~[CHAPTERS_PER_PAGE] per page), fetch that range with
     * bounded concurrency, then extend until a page adds no new chapters (the clamp) or is short
     * (the real last page) — never relying on an empty page or [MAX_CHAPTER_PAGES] to stop. That
     * termination is what keeps a large book from firing ~[MAX_CHAPTER_PAGES] redundant requests
     * and tripping the site's rate limiter. A page inside the known range that never recovers from a
     * transient failure aborts the whole load rather than silently leaving a hole; a gated
     * (403/redirect) page degrades. See [fetchChapterPage].
     */
    internal suspend fun loadAllChapters(bookId: String): List<ChapterInfo> =
        assembleChapters { page -> fetchChapterPage(bookId, page) }

    /** Paging orchestration, decoupled from page-fetching so the termination/gap/degrade rules are unit-testable. */
    internal suspend fun assembleChapters(fetchPage: suspend (Int) -> PageOutcome): List<ChapterInfo> {
        val acc = ChapterAccumulator()
        val firstChapters = (fetchPage(1) as? PageOutcome.Loaded)?.chapters
        if (firstChapters.isNullOrEmpty()) return emptyList()
        acc.add(firstChapters)

        val estimatedPages = estimatePageCount(firstChapters)
        collectKnownRange(acc, estimatedPages, fetchPage)
        extendBeyondEstimate(acc, estimatedPages + 1, fetchPage)
        return acc.sortedByNumber()
    }

    private fun estimatePageCount(firstChapters: List<ChapterInfo>): Int {
        // Page 1 holds the newest, highest-numbered chapters (~CHAPTERS_PER_PAGE per page), so the max
        // number estimates the page count. It can undercount slightly (sub-chapters), hence the extend.
        val maxNumber = firstChapters.maxOfOrNull { it.number ?: 0.0 } ?: 0.0
        return ((maxNumber / CHAPTERS_PER_PAGE).toInt() + 1).coerceIn(1, MAX_CHAPTER_PAGES)
    }

    /**
     * Fetch the known-dense range (pages 2..[estimatedPages]) in parallel. Each page retries its own
     * transient failures; a gated (premium) page is skipped, but a page that never recovers aborts the
     * whole load rather than silently leaving a hole in the middle of the chapter list.
     */
    private suspend fun collectKnownRange(
        acc: ChapterAccumulator,
        estimatedPages: Int,
        fetchPage: suspend (Int) -> PageOutcome
    ) {
        if (estimatedPages <= 1) return
        coroutineScope {
            val semaphore = Semaphore(CHAPTER_PAGE_CONCURRENCY)
            val outcomes = (2..estimatedPages).map { page ->
                async { semaphore.withPermit { page to fetchPage(page) } }
            }.awaitAll().sortedBy { it.first }
            for ((page, outcome) in outcomes) {
                when (outcome) {
                    is PageOutcome.Loaded -> acc.add(outcome.chapters)
                    PageOutcome.Gated -> Unit
                    PageOutcome.Failed ->
                        throw IOException("Novelight chapter page $page failed after retries")
                }
            }
        }
    }

    /**
     * Extend past the (sometimes-undercounting) estimate, stopping at the real last page — detected by
     * a short page or one that only repeats already-seen URLs (the endpoint's out-of-range clamp) — so
     * a large book never fires ~[MAX_CHAPTER_PAGES] redundant requests.
     */
    private suspend fun extendBeyondEstimate(
        acc: ChapterAccumulator,
        startPage: Int,
        fetchPage: suspend (Int) -> PageOutcome
    ) {
        var page = startPage
        var advancing = true
        while (advancing && page <= MAX_CHAPTER_PAGES) {
            val chapters = (fetchPage(page) as? PageOutcome.Loaded)?.chapters
            val added = chapters?.let { acc.add(it) } ?: 0
            advancing = chapters != null && added > 0 && chapters.size >= CHAPTERS_PER_PAGE
            page++
        }
    }

    /** Accumulates chapters across pages, de-duping by URL (the clamp repeats a page's URLs). */
    private class ChapterAccumulator {
        private val seen = HashSet<String>()
        private val all = ArrayList<ChapterInfo>()

        fun add(chapters: List<ChapterInfo>): Int {
            var added = 0
            for (chapter in chapters) if (seen.add(chapter.url)) {
                all.add(chapter)
                added++
            }
            return added
        }

        fun sortedByNumber(): List<ChapterInfo> = all.sortedBy { it.number ?: Double.MAX_VALUE }
    }

    internal sealed interface PageOutcome {
        data class Loaded(val chapters: List<ChapterInfo>) : PageOutcome
        object Gated : PageOutcome
        object Failed : PageOutcome
    }

    /** Fetch one chapter-pagination page, retrying transient failures (429/5xx/timeout) with backoff. */
    internal suspend fun fetchChapterPage(bookId: String, page: Int): PageOutcome {
        val url = "$baseUrl/book/ajax/chapter-pagination?book_id=$bookId&page=$page"
        var attempt = 0
        var outcome: PageOutcome? = null
        while (outcome == null) {
            outcome = when (val result = ajaxFetch(url)) {
                is FetchResult.Body -> PageOutcome.Loaded(parseChapterPaginationHtml(jsonField(result.text, "html")))
                FetchResult.Gated -> PageOutcome.Gated
                is FetchResult.Retryable ->
                    if (attempt >= MAX_PAGE_RETRIES) {
                        PageOutcome.Failed
                    } else {
                        delay(HttpRetry.nextRetryDelayMs(result.retryAfterMs, url, attempt))
                        attempt++
                        null
                    }
            }
        }
        return outcome
    }

    // --- HTTP --------------------------------------------------------------------------------

    private fun jsonField(body: String, field: String): String =
        runCatching { JSONObject(body).optString(field) }.getOrNull().orEmpty()

    private sealed interface FetchResult {
        data class Body(val text: String) : FetchResult
        object Gated : FetchResult
        data class Retryable(val retryAfterMs: Long?, val cause: Exception? = null) : FetchResult
    }

    /**
     * One raw XHR GET. 2xx → [FetchResult.Body]; 403 or a (non-followed) redirect → [FetchResult.Gated]
     * (premium content); 408/429/5xx or a network error → [FetchResult.Retryable]; other 4xx → gated.
     */
    private fun ajaxFetch(url: String): FetchResult {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> FetchResult.Body(response.body.string())
                    response.code == HTTP_FORBIDDEN || response.code in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX ->
                        FetchResult.Gated
                    HttpRetry.shouldRetryResponseCode(response.code) ->
                        FetchResult.Retryable(HttpRetry.parseRetryAfterMs(response.header("Retry-After")))
                    else -> FetchResult.Gated
                }
            }
        } catch (e: IOException) {
            FetchResult.Retryable(retryAfterMs = null, cause = e)
        }
    }

    private fun ajaxGet(url: String): String = when (val result = ajaxFetch(url)) {
        is FetchResult.Body -> result.text
        else -> throw IOException("Unexpected response for $url")
    }
}
