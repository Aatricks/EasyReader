package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.util.TextHeuristics
import io.aatricks.easyreader.util.TextUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlParser @Inject constructor() {

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MULTIPLE_SPACES_REGEX = Regex(" +")
        private val DOUBLE_NEWLINE_REGEX = Regex("\\n\\s*\\n")
        private val CHAPTER_CLEANUP_PATTERN = Regex("(?i)^(?:chapter|chap|ch|ch\\.)[\\s:\\-\\.]*\\d+\\b.*")
        private val CHAPTER_WORD_PATTERN = Regex("(?i)chapter")
        private val DIGIT_ONLY_REGEX = Regex("^\\d+")
        
        private val MANGA_IMAGE_SELECTOR = listOf(
            ".container-chapter-reader img",
            ".vung-doc img",
            ".reader-content img",
            ".chapter-content img",
            ".chapter-img img",
            ".read-content img",
            ".container-reading img",
            "div.page-break img"
        ).joinToString(", ")

        private val NOVEL_CONTENT_SELECTOR = listOf(
            "article p",
            ".content p",
            ".post-content p",
            ".entry-content p",
            "#content p",
            "main p",
            "div.chapter-c p"
        ).joinToString(", ")
    }

    fun parse(document: Document, url: String): List<ContentElement> {
        cleanDocument(document)

        val images = parseImages(document, url)
        val paragraphs = parseParagraphs(document)

        val filteredParagraphs = filterParagraphs(paragraphs, document.title())

        // If it looks like a manga (many images or few paragraphs), return images
        if (images.size > 5 || (images.isNotEmpty() && filteredParagraphs.size < 10)) {
            return images
        }

        if (filteredParagraphs.isEmpty()) {
            return if (images.isNotEmpty()) images else emptyList()
        }

        return mergeAndFormatParagraphs(filteredParagraphs)
    }

    private fun cleanDocument(document: Document): Unit {
        // Remove advertisements
        val adSelectors = listOf(
            ".ads-banner", "[class*=\"ads-banner\"]", "[class*=\"bats-ads\"]", ".ads-responsive",
            ".ads-chapter-bottom", ".bats-detail-bottom-pos-1-detail-bottom-72", ".sh-recommend",
            ".cm-info", ".next-chapter-img", "[id*=\"ads-\"]", "[class*=\"footer-ads\"]",
            ".ads-contain", ".banner-owner", ".banner-ads", "[class*=\"ads-contain\"]"
        )
        document.select(adSelectors.joinToString(", ")).remove()

        // Remove credit/recommend images
        document.select("img[alt*='credit'], img[alt*='recommend'], img[src*='credit'], img[src*='recommend'], img[alt*='ei0qg'], img[title*='ei0qg']").remove()
    }

    private fun parseImages(document: Document, url: String): List<ContentElement.Image> {
        val imageElements = document.select(MANGA_IMAGE_SELECTOR)
        if (imageElements.isEmpty()) return emptyList()

        val adDomains = listOf(
            "yougetwhatyoupayfor.net", "bemobtrcks.com", "xpoker24.com",
            "coolgamesunblocked.com", "crazygamesunblocked.net", "abcya3.games", "eos.co.com"
        )
        
        val images = mutableListOf<ContentElement.Image>()
        imageElements.forEach { element ->
            if (isAdImage(element, adDomains)) return@forEach

            val src = element.attr("data-src").ifEmpty { element.attr("data-original") }.ifEmpty { element.attr("src") }
            if (src.isBlank() || isThumbnailOrLogo(src, adDomains)) return@forEach

            val absoluteUrl = resolveImageUrl(src, url)
            
            // Only trust dimensions from HTML for PDF/ePub local files, not from manga sites
            // which often have incorrect or placeholder values (like width=3000 height=1000)
            val isMangaSite = url.contains("mangabat") || url.contains("manganato") || 
                              url.contains("novelfire") || url.contains("manhwa")
            
            val width = if (isMangaSite) 0 else element.attr("width").toIntOrNull() ?: element.attr("data-width").toIntOrNull() ?: 0
            val height = if (isMangaSite) 0 else element.attr("height").toIntOrNull() ?: element.attr("data-height").toIntOrNull() ?: 0

            images.add(ContentElement.Image(url = absoluteUrl, width = width, height = height))
        }

        return filterLastMangaImage(images, url)
    }

    private fun isAdImage(element: Element, adDomains: List<String>): Boolean {
        val parentLink = element.parents().firstOrNull { it.tagName() == "a" } ?: return false
        val href = parentLink.attr("href")
        return adDomains.any { href.contains(it) } || href.contains("facebook.com") || href.contains("twitter.com")
    }

    private fun isThumbnailOrLogo(src: String, adDomains: List<String>): Boolean {
        return src.contains("/thumb/") || src.contains("og-image-bat.png") || 
               src.contains("logo") || src.contains("banner") || 
               adDomains.any { src.contains(it) }
    }

    private fun resolveImageUrl(src: String, pageUrl: String): String {
        if (src.startsWith("http")) return src
        
        val httpUrl = pageUrl.toHttpUrlOrNull()
        val domain = if (httpUrl != null) "${httpUrl.scheme}://${httpUrl.host}" else ""
        return if (src.startsWith("/")) {
            "$domain$src"
        } else {
            val base = pageUrl.substringBeforeLast("/")
            "$base/$src"
        }
    }

    private fun filterLastMangaImage(images: MutableList<ContentElement.Image>, url: String): List<ContentElement.Image> {
        if (images.size <= 5 || !(url.contains("mangabats.com") || url.contains("manganato.com"))) {
            return images
        }

        val lastImg = images.last()
        val firstHost = images.first().url.toHttpUrlOrNull()?.host.orEmpty()
        val lastHost = lastImg.url.toHttpUrlOrNull()?.host.orEmpty()
        
        val isSuspect = lastImg.url.contains("recommend") || lastImg.url.contains("banner") || 
                        lastImg.url.contains("next") || lastImg.url.contains("/thumb/") || 
                        (lastHost.isNotBlank() && firstHost != lastHost)

        if (isSuspect) {
            images.removeAt(images.size - 1)
        }
        return images
    }

    private fun parseParagraphs(document: Document): List<String> {
        val novelElements = document.select(NOVEL_CONTENT_SELECTOR)
        if (novelElements.isNotEmpty()) {
            return novelElements.mapNotNull { extractTextPreservingLineBreaks(it).takeIf { t -> t.isNotBlank() } }
        }

        return document.select("p").mapNotNull { extractTextPreservingLineBreaks(it).takeIf { t -> t.isNotBlank() } }
    }

    private fun filterParagraphs(paragraphs: List<String>, title: String?): List<String> {
        val cleanTitle = title?.trim()?.lowercase()
        return paragraphs.filter { raw ->
            val p = raw.trim()
            val lowerP = p.lowercase()
            
            if (p.isEmpty() || p.matches(DIGIT_ONLY_REGEX) || CHAPTER_CLEANUP_PATTERN.containsMatchIn(p)) return@filter false
            if (p.length <= 80 && p.contains(CHAPTER_WORD_PATTERN) && p.any { it.isDigit() }) return@filter false
            if (cleanTitle != null && (lowerP == cleanTitle || lowerP.startsWith(cleanTitle))) return@filter false
            true
        }
    }

    private fun mergeAndFormatParagraphs(paragraphs: List<String>): List<ContentElement.Text> {
        val merged = mutableListOf<String>()
        var idx = 0
        while (idx < paragraphs.size) {
            val cur = paragraphs[idx].trim()
            if (cur.isEmpty()) { idx++; continue }

            if (idx + 1 < paragraphs.size) {
                val next = paragraphs[idx + 1].trim()
                if (next.isNotEmpty() && shouldMerge(cur, next)) {
                    val sb = StringBuilder(cur)
                    sb.append(" ").append(next)
                    idx += 2
                    // Deep merging
                    while (idx < paragraphs.size) {
                        val peek = paragraphs[idx].trim()
                        if (peek.isEmpty()) { idx++; continue }
                        if (shouldStopMerging(sb, peek)) break
                        sb.append(" ").append(peek)
                        idx++
                    }
                    merged.add(sb.toString().replace(MULTIPLE_SPACES_REGEX, " "))
                    continue
                }
            }
            merged.add(cur)
            idx++
        }

        val joined = merged.distinct().joinToString("\n\n")
        val formatted = TextUtils.formatChapterText(joined)
        return formatted.split(DOUBLE_NEWLINE_REGEX)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { ContentElement.Text(it) }
    }

    private fun shouldMerge(cur: String, next: String): Boolean {
        return TextHeuristics.shouldMergeSentenceFragments(
            current = cur,
            next = next,
            maxWordCount = 8,
            preventDualColonMerge = true
        )
    }

    private fun shouldStopMerging(cur: CharSequence, peek: String): Boolean {
        return TextHeuristics.shouldStopGreedyMerge(cur, peek)
    }

    private fun extractTextPreservingLineBreaks(element: Element): String {
        if (element.selectFirst("br") == null) return element.text()
        val sb = StringBuilder()
        element.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) sb.append(node.text())
                else if (node is Element && node.tagName() == "br") sb.append("\n")
            }
            override fun tail(node: Node, depth: Int) {}
        })
        return sb.toString()
    }
}
