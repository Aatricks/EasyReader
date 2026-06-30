package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import javax.inject.Inject

/**
 * Source with no fixed host. Search query starting with http(s) triggers heuristic series detail
 * scrape. Selectors tried in order: Madara WordPress theme → og:meta + generic chapter-link
 * patterns. Browse and tag listings unsupported.
 */
class SmartSource @Inject constructor(
    override val preferencesManager: PreferencesManager,
    override val okHttpClient: okhttp3.OkHttpClient
) : BaseJsoupSource(preferencesManager, okHttpClient) {
    override val name = "Smart Scrape"
    override val baseUrl = ""
    override val version = "1.0.0"

    companion object {
        private val URL_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("(?:^|[^0-9])(\\d+(?:\\.\\d+)?)(?=[^0-9]|$)")
        private val CHAPTER_HREF_HINTS = listOf("/chapter-", "/chapter/", "-chapter-", "/ch-", "/ch/")
        private const val SITE_SUFFIX_REGEX = "\\s*[|\\-–—]\\s*[^|\\-–—]+$"
    }

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = emptyList()

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val trimmed = query.trim()
        if (!URL_PREFIX.containsMatchIn(trimmed)) return@io emptyList()
        runCatching { listOf(getNovelDetails(trimmed)) }.getOrDefault(emptyList())
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = fetchDocument(url)
        val title = extractTitle(document)
        val coverUrl = extractCover(document, url)
        val summary = extractSummary(document)
        val author = extractAuthor(document)
        val chapters = extractChapters(document, url)

        ExploreItem(
            title = title.ifBlank { url },
            url = url,
            coverUrl = coverUrl?.ifBlank { null },
            author = author?.ifBlank { null },
            summary = summary?.ifBlank { null },
            chapterCount = chapters.size,
            source = name,
            readingUrl = chapters.firstOrNull()?.url,
            chapters = chapters
        )
    }

    internal fun fetchDocument(url: String): Document {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", refererFor(url))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val html = response.body.string()
            return Jsoup.parse(html, url)
        }
    }

    private fun refererFor(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}/"
    }.getOrDefault(url)

    private fun extractTitle(document: Document): String {
        document.selectFirst(".post-title h1, .post-title h3, .post-title h5")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }

        document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        val og = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        if (og.isNotBlank()) return og.replace(Regex(SITE_SUFFIX_REGEX), "").trim()

        val title = document.selectFirst("title")?.text()?.trim().orEmpty()
        return title.replace(Regex(SITE_SUFFIX_REGEX), "").trim()
    }

    private fun extractCover(document: Document, pageUrl: String): String? {
        document.selectFirst(".summary_image img, .manga-poster img, .thumb img, .info-image img")
            ?.absOrFind(pageUrl)?.takeIf { it.isNotBlank() }?.let { return it }

        document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return absolutize(it, pageUrl) }

        document.selectFirst("meta[name=twitter:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return absolutize(it, pageUrl) }

        return document.selectFirst("article img, main img, .content img")
            ?.absOrFind(pageUrl)?.takeIf { it.isNotBlank() }
    }

    private fun extractSummary(document: Document): String? {
        document.firstNonBlankText(
            listOf(
                ".description-summary .summary__content, .summary__content, .manga-summary, " +
                    "#description, .description, .post-content_item:has(h5:contains(Summary)) p"
            )
        )?.let { return it }
        return document.metaContent(property = "og:description", name = "description")
    }

    private fun extractAuthor(document: Document): String? {
        document.firstNonBlankText(
            listOf(
                ".author-content a, .author-content",
                ".artist-content a, .artist-content"
            )
        )?.let { return it }
        document.metaContent(name = "author")?.let { return it }
        return document.selectFirst("a[href*=author], a[href*=artist]")?.text()?.trim()
    }

    private fun extractChapters(document: Document, pageUrl: String): List<ChapterInfo> {
        val candidates = mutableListOf<Element>()
        candidates.addAll(document.select("li.wp-manga-chapter a, .wp-manga-chapter a, .listing-chapters_wrap a"))
        if (candidates.isEmpty()) {
            candidates.addAll(document.select("a[href]").filter { element ->
                val href = element.attr("href").lowercase()
                CHAPTER_HREF_HINTS.any { hint -> href.contains(hint) }
            })
        }

        val byUrl = LinkedHashMap<String, ChapterInfo>()
        for (anchor in candidates) {
            val absoluteUrl = anchor.absoluteHref(pageUrl)
            if (absoluteUrl.isBlank()) continue
            if (!looksLikeChapterUrl(absoluteUrl)) continue

            val title = chapterTitleFor(anchor)
            if (title.isBlank()) continue

            val existing = byUrl[absoluteUrl]
            if (existing == null || existing.title.length < title.length) {
                byUrl[absoluteUrl] = ChapterInfo(title = title, url = absoluteUrl)
            }
        }

        return byUrl.values.sortedBy { extractChapterNumberFromUrl(it.url) }
    }

    private fun looksLikeChapterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return CHAPTER_HREF_HINTS.any { hint -> lower.contains(hint) }
    }

    private fun chapterTitleFor(anchor: Element): String {
        val raw = anchor.text().trim()
        if (raw.isBlank()) return ""
        return raw.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { raw }
    }

    private fun extractChapterNumberFromUrl(url: String): Double {
        val tail = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val match = CHAPTER_NUMBER_REGEX.find(tail) ?: CHAPTER_NUMBER_REGEX.find(url)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: Double.MAX_VALUE
    }

    private fun absolutize(value: String, pageUrl: String): String {
        if (value.isBlank()) return ""
        if (value.startsWith("http", ignoreCase = true)) return value
        return runCatching { URI(pageUrl).resolve(value).toString() }.getOrDefault(value)
    }

    private fun Element.absOrFind(pageUrl: String): String {
        val direct = attr("abs:src").ifBlank { findImage() }
        return if (direct.startsWith("http", ignoreCase = true)) direct else absolutize(direct, pageUrl)
    }

    private fun Element.absoluteHref(pageUrl: String): String {
        val abs = attr("abs:href")
        if (abs.isNotBlank()) return abs
        val raw = attr("href")
        return absolutize(raw, pageUrl)
    }
}
