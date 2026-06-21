package io.aatricks.easyreader.data.repository.source

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/**
 * URL rules for Novelight (novelight.net) shared between [NovelightSource] and the reader's
 * content loader. Novelight is a Django + XHR site: chapter prose is not in the static
 * `/book/chapter/{id}` page (a "Loading…" shell) but is served as JSON by the
 * `/book/ajax/read-chapter/{id}` endpoint, which 403s unless `X-Requested-With: XMLHttpRequest`
 * is sent. Keeping these rules in one place means the source and the reader can't disagree.
 */
object NovelightUrls {
    const val HOST = "novelight.net"
    const val BASE_URL = "https://novelight.net"

    private val CHAPTER_PATH = Regex("^/book/chapter/(\\d+)/?$")

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    private fun pathOf(url: String): String? =
        runCatching { URI(url).path }.getOrNull()

    fun isNovelightHost(url: String): Boolean = hostOf(url) == HOST

    /** Chapter id if [url] is a Novelight chapter reading URL (`/book/chapter/{id}`), else null. */
    fun chapterId(url: String): String? {
        if (hostOf(url) != HOST) return null
        return CHAPTER_PATH.find(pathOf(url).orEmpty())?.groupValues?.get(1)
    }

    fun readChapterUrl(chapterId: String): String = "$BASE_URL/book/ajax/read-chapter/$chapterId"

    /** Novelight's `/ajax/` and `/book/ajax/` endpoints 403 without the XHR header. */
    fun requiresXhrHeader(url: String): Boolean =
        hostOf(url) == HOST && (pathOf(url)?.contains("/ajax/") == true)

    /**
     * Pull the chapter prose out of a `read-chapter` JSON body and return it as clean,
     * script/ad-free HTML the [io.aatricks.easyreader.data.repository.HtmlParser] can read.
     * Returns null when the chapter is empty or gated (premium), so the reader surfaces an
     * empty/"couldn't load" state rather than rendering ad markup or raw JSON.
     */
    fun extractChapterContentHtml(jsonBody: String): String? {
        val content = runCatching { JSONObject(jsonBody).optString("content") }.getOrNull()
        return sanitizeChapterContent(content)
    }

    /** Strip scripts/ads from a raw chapter-content fragment and re-wrap the prose. */
    internal fun sanitizeChapterContent(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val fragment = Jsoup.parseBodyFragment(content)
        fragment.select("script, .advertisment, ins, iframe").remove()
        val root = fragment.selectFirst(".chapter-text") ?: fragment.body()
        val inner = root.html().trim()
        return if (inner.isBlank()) null else "<div class=\"chapter-text\">$inner</div>"
    }
}
