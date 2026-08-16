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
        private val MULTIPLE_SPACES_REGEX = Regex(" +")
        private val DOUBLE_NEWLINE_REGEX = Regex("\\n\\s*\\n")
        private val CHAPTER_CLEANUP_PATTERN = Regex("(?i)^(?:chapter|chap|ch|ch\\.)[\\s:\\-\\.]*\\d+\\b.*")
        private val CHAPTER_WORD_PATTERN = Regex("(?i)chapter")
        private val DIGIT_ONLY_REGEX = Regex("^\\d+")

        // Compared against a whole path segment with its extension stripped, never as a
        // substring — see isLikelyDecorativeUrl.
        private val DECORATIVE_PATH_SEGMENTS = setOf(
            "ad", "ads", "advert", "adverts",
            "avatar", "avatars",
            "banner", "banners",
            "cover", "covers",
            "logo", "logos",
            "loadingimg", "loading-img",
            "og-image", "og-image-bat",
            "thumb", "thumbs", "thumbnail", "thumbnails"
        )

        private val MANGA_IMAGE_SELECTOR = listOf(
            ".container-chapter-reader img",
            ".vung-doc img",
            ".reader-content img",
            ".chapter-content img",
            ".chapter-img img",
            ".read-content img",
            ".container-reading img",
            "div.page-break img",
            "img[data-page-index]",
            "div[data-page] img"
        ).joinToString(", ")

        private val NOVEL_CONTENT_SELECTOR = listOf(
            "article p",
            ".content p",
            ".post-content p",
            ".entry-content p",
            "#content p",
            "main p",
            "div.chapter-c p",
            // Novelight serves chapter prose as <div>-per-paragraph inside .chapter-text
            // (delivered by its read-chapter XHR; see NovelightUrls).
            ".chapter-text > div"
        ).joinToString(", ")
    }

    fun parse(document: Document, url: String): List<ContentElement> {
        cleanDocument(document)

        var images = parseImages(document, url)
        val paragraphs = parseParagraphs(document)

        val filteredParagraphs = filterParagraphs(paragraphs, document.title())

        // Some manga sites (Mangabat, Asura) embed the chapter image list in inline JS
        // (`chapterImages = [...]`, Astro page-island JSON, etc.) and only hardcode the
        // cover image as a static <img>. Without this fallback the parser sees 1 image,
        // the downloader marks the chapter "complete" after fetching the cover, and the
        // reader shows a single page offline. Merge JS-extracted URLs into the image list
        // for chapter URLs where the static parse looks suspiciously thin.
        if (isChapterPage(url) && images.size <= 2) {
            val jsImages = extractInlineScriptImageUrls(document, url, alreadyKnown = images.map { it.url }.toSet())
            if (jsImages.isNotEmpty()) {
                images = (images + jsImages.map { ContentElement.Image(url = it, width = 0, height = 0) })
                    .distinctBy { it.url }
            }
        }

        // Chapter pages from manga sites legitimately have many paragraphs of footer text
        // (comments, ads, "if you want to read free manga..."). If any chapter image was
        // extracted from a manga reader container, trust that this is image content and
        // ignore the boilerplate paragraphs — otherwise the user sees the footer text and
        // none of the actual manga pages.
        if (images.isNotEmpty() && (images.size > 5 || isChapterPage(url) || filteredParagraphs.size < 10)) {
            return images
        }

        if (filteredParagraphs.isEmpty()) {
            return if (images.isNotEmpty()) images else emptyList()
        }

        return mergeAndFormatParagraphs(filteredParagraphs)
    }

    /**
     * Mangabat / Asura / other JS-rendered chapter pages embed the real page-image list
     * inside `<script>` blocks. This pulls them out so the parser doesn't have to wait
     * for a real browser to execute JS. Two patterns covered:
     *
     *  - Mangabat-style: `var chapterImages = ["slug/0.webp", ...]` plus `var cdns = ["https://..."]`.
     *    Each entry is appended to the first CDN to form an absolute URL.
     *  - Astro/SPA-style: `"url":"https://cdn..."` repeated inside a serialized state blob.
     *
     * Both extractors are intentionally narrow — they look for image-y URL strings and
     * combine them with the prefix the page itself defines. Returned URLs are filtered to
     * http(s) and to known image extensions to avoid pulling in script/CSS asset URLs.
     */
    private fun extractInlineScriptImageUrls(
        document: Document,
        pageUrl: String,
        alreadyKnown: Set<String>
    ): List<String> {
        val scriptBlob = document.select("script").joinToString("\n") { it.data() }
        val raw = document.html()
        val collected = LinkedHashSet<String>()

        val cdnMatch = Regex("""(?:var\s+)?cdns\s*=\s*\[\s*"([^"]+)"""")
            .find(scriptBlob)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.trimEnd('/')
        Regex("""(?:var\s+)?chapterImages\s*=\s*\[([^\]]+)\]""").find(scriptBlob)?.let { match ->
            val arr = match.groupValues[1]
            Regex(""""([^"]+\.(?:webp|jpg|jpeg|png|gif))"""", RegexOption.IGNORE_CASE)
                .findAll(arr)
                .forEach { m ->
                    val path = m.groupValues[1].replace("\\/", "/").trimStart('/')
                    val abs = when {
                        path.startsWith("http") -> path
                        cdnMatch != null -> "$cdnMatch/$path"
                        else -> resolveImageUrl(path, pageUrl)
                    }
                    collected.add(abs)
                }
        }

        Regex(""""url"\s*(?::|,)\s*\[?\s*\d*\s*,?\s*"(https?://[^"]+\.(?:webp|jpg|jpeg|png|gif))""""", RegexOption.IGNORE_CASE)
            .findAll(raw)
            .forEach { collected.add(it.groupValues[1].replace("\\/", "/")) }

        return collected
            .asSequence()
            .filter { it !in alreadyKnown }
            .filter { it.startsWith("http") }
            .filter { !isLikelyDecorativeUrl(it) }
            .toList()
    }

    /**
     * Decorative-asset check, matched against whole path segments rather than raw substrings.
     * The substring form dropped real pages: "ads" also matches "/wp-content/uploads/", the
     * standard media path for every WordPress/Madara-hosted manga site, so those chapters
     * downloaded a handful of images, reported complete, and opened short offline.
     */
    private fun isLikelyDecorativeUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#')
            .lowercase()
            .split('/')
            .any { segment -> segment.substringBeforeLast('.') in DECORATIVE_PATH_SEGMENTS }

    private fun isChapterPage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/chapter/") ||
            lower.contains("/chapter-") ||
            lower.contains("-chapter-") ||
            lower.contains("/manga/") && lower.contains("chapter")
    }

    private fun cleanDocument(document: Document): Unit {
        // Remove advertisements
        val adSelectors = listOf(
            ".ads-banner", "[class*=\"ads-banner\"]", "[class*=\"bats-ads\"]", ".ads-responsive",
            ".ads-chapter-bottom", ".bats-detail-bottom-pos-1-detail-bottom-72", ".sh-recommend",
            ".cm-info", ".next-chapter-img", "[id*=\"ads-\"]", "[class*=\"footer-ads\"]",
            ".ads-contain", ".banner-owner", ".banner-ads", "[class*=\"ads-contain\"]",
            ".advertisment"
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
        return isLikelyDecorativeUrl(src) || adDomains.any { src.contains(it) }
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

        // A host that differs from the first image's used to count as suspect on its own.
        // Sharded page CDNs serve one chapter from several hosts, so that rule silently
        // dropped a real last page. Only the name-based markers are trustworthy.
        val isSuspect = lastImg.url.contains("recommend") ||
            lastImg.url.contains("next") ||
            isLikelyDecorativeUrl(lastImg.url)

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
            // Guard against a blank page title: lowerP.startsWith("") is always true and would
            // drop every paragraph (e.g. Novelight's title-less wrapped chapter content).
            if (!cleanTitle.isNullOrEmpty() &&
                (lowerP == cleanTitle || lowerP.startsWith(cleanTitle))
            ) {
                return@filter false
            }
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
