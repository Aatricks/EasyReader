package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.util.TextUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlParser @Inject constructor() {

    companion object {
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
        private val CONTINUATION_WORDS = setOf(
            "of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with"
        )
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
        // Remove advertisements
        document.select("""
            .ads-banner, [class*="ads-banner"], [class*="bats-ads"], .ads-responsive, .ads-chapter-bottom,
            .bats-detail-bottom-pos-1-detail-bottom-72, .sh-recommend, .cm-info, .next-chapter-img,
            [id*="ads-"], [class*="footer-ads"], .ads-contain, .banner-owner, .banner-ads, [class*="ads-contain"]
        """.trimIndent().replace("\n", "")).remove()

        document.select("img[alt*='credit'], img[alt*='recommend'], img[src*='credit'], img[src*='recommend'], img[alt*='ei0qg'], img[title*='ei0qg']").remove()

        val title = document.title().takeIf { it.isNotBlank() }
        val imagesFromSelectors = mutableListOf<ContentElement.Image>()
        val imageElements = document.select(MANGA_IMAGE_SELECTOR)
        
        if (imageElements.isNotEmpty()) {
            val adDomains = listOf("yougetwhatyoupayfor.net", "bemobtrcks.com", "xpoker24.com", "coolgamesunblocked.com", "crazygamesunblocked.net", "abcya3.games", "eos.co.com")
            
            imageElements.forEach { element ->
                val parentLink = element.parents().firstOrNull { it.tagName() == "a" }
                if (parentLink != null) {
                    val href = parentLink.attr("href")
                    if (adDomains.any { href.contains(it) } || href.contains("facebook.com") || href.contains("twitter.com")) return@forEach
                }

                val src = element.attr("data-src").ifEmpty { element.attr("data-original") }.ifEmpty { element.attr("src") }

                if (src.isNotBlank()) {
                    if (src.contains("/thumb/") || src.contains("og-image-bat.png") || src.contains("logo") || src.contains("banner") || adDomains.any { src.contains(it) }) return@forEach

                    val absoluteUrl = if (src.startsWith("http")) src else {
                        val domain = try { URL(url).let { "${it.protocol}://${it.host}" } } catch (e: Exception) { "" }
                        if (src.startsWith("/")) "$domain$src" else {
                            val base = url.substringBeforeLast("/")
                            "$base/$src"
                        }
                    }
                    imagesFromSelectors.add(ContentElement.Image(url = absoluteUrl))
                }
            }
            
            if ((url.contains("mangabats.com") || url.contains("manganato.com")) && imagesFromSelectors.size > 5) {
                val lastImg = imagesFromSelectors.last()
                val firstHost = try { URL(imagesFromSelectors.first().url).host } catch (_: Exception) { "" }
                val lastHost = try { URL(lastImg.url).host } catch (_: Exception) { "" }
                
                if (lastImg.url.contains("recommend") || lastImg.url.contains("banner") || lastImg.url.contains("next") || 
                    lastImg.url.contains("/thumb/") || (lastHost.isNotBlank() && firstHost != lastHost)) {
                    imagesFromSelectors.removeAt(imagesFromSelectors.size - 1)
                }
            }
        }

        val paragraphs = mutableListOf<String>()
        val novelElements = document.select(NOVEL_CONTENT_SELECTOR)
        
        if (novelElements.isNotEmpty()) {
            novelElements.forEach { element ->
                val text = extractTextPreservingLineBreaks(element)
                if (text.isNotBlank()) paragraphs.add(text)
            }
        }

        if (paragraphs.isEmpty() && imagesFromSelectors.size <= 5) {
            document.select("p").forEach { element ->
                val text = extractTextPreservingLineBreaks(element)
                if (text.isNotBlank()) paragraphs.add(text)
            }
        }

        val filteredParagraphs = paragraphs.filter { raw ->
            val p = raw.trim()
            if (p.isEmpty() || p.matches(DIGIT_ONLY_REGEX) || CHAPTER_CLEANUP_PATTERN.containsMatchIn(p)) return@filter false
            if (p.length <= 80 && p.contains(CHAPTER_WORD_PATTERN) && p.any { it.isDigit() }) return@filter false
            if (title != null && (p.equals(title.trim(), ignoreCase = true) || p.startsWith(title.trim()))) return@filter false
            true
        }

        if (imagesFromSelectors.size > 5 || (imageElements.isNotEmpty() && filteredParagraphs.size < 10)) {
            return imagesFromSelectors
        }

        if (filteredParagraphs.isEmpty()) {
            return if (imagesFromSelectors.isNotEmpty()) imagesFromSelectors else emptyList()
        }

        val merged = mutableListOf<String>()
        var idx = 0
        while (idx < filteredParagraphs.size) {
            var cur = filteredParagraphs[idx].trim()
            if (cur.isEmpty()) { idx++; continue }

            if (idx + 1 < filteredParagraphs.size) {
                val next = filteredParagraphs[idx + 1].trim()
                if (next.isNotEmpty()) {
                    val lastChar = cur.lastOrNull()
                    val lastW = cur.trim().split(WHITESPACE_REGEX).lastOrNull()?.lowercase() ?: ""
                    val wordCount = cur.split(WHITESPACE_REGEX).size

                    val shouldMerge = (lastChar != null && !SENTENCE_ENDERS.contains(lastChar)) &&
                            (wordCount <= 8 || lastW in CONTINUATION_WORDS || lastW.length <= 4) &&
                            !(cur.contains(':') && next.contains(':'))

                    if (shouldMerge) {
                        cur = (cur + " " + next).replace(MULTIPLE_SPACES_REGEX, " ")
                        idx += 2
                        while (idx < filteredParagraphs.size) {
                            val peek = filteredParagraphs[idx].trim()
                            if (peek.isEmpty()) { idx++; continue }
                            val peekFirst = peek.firstOrNull()
                            if (peekFirst != null && peekFirst.isUpperCase() && cur.trim().lastOrNull()?.let { SENTENCE_ENDERS.contains(it) } == true) break
                            cur = (cur + " " + peek).replace(MULTIPLE_SPACES_REGEX, " ")
                            idx++
                        }
                        merged.add(cur)
                        continue
                    }
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
