package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.CustomSourceChapterOrder
import io.aatricks.novelscraper.data.model.CustomSourceContentKind
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.util.TextUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomSourceRecipeEngine @Inject constructor() {

    companion object {
        private val DOUBLE_NEWLINE_REGEX = Regex("\\n\\s*\\n")
    }

    fun extractSeriesDetails(
        recipe: CustomSourceRecipeDefinition,
        document: Document,
        pageUrl: String
    ): ExploreItem {
        val title = document.select(recipe.titleSelector).firstOrNull()?.text()?.trim()
            ?.ifBlank { null }
            ?: document.title().ifBlank { throw IllegalStateException("Missing title for recipe ${recipe.displayName}") }

        val chapters = extractChapters(recipe, document, pageUrl)
        val readingUrl = recipe.readingUrlSelector
            ?.let { selector -> document.select(selector).firstOrNull()?.absoluteHref(pageUrl) }
            ?.takeIf { it.isNotBlank() }
            ?: chapters.firstOrNull()?.url
            ?: throw IllegalStateException("No reading URL could be extracted for $pageUrl")

        val summary = recipe.summarySelector
            ?.let { selector -> document.select(selector).firstOrNull()?.text()?.trim() }
            ?.takeIf { it.isNotBlank() }

        val coverUrl = recipe.coverSelector
            ?.let { selector -> document.select(selector).firstOrNull()?.findImageUrl(pageUrl) }
            ?.takeIf { it.isNotBlank() }

        return ExploreItem(
            title = title,
            url = pageUrl,
            coverUrl = coverUrl,
            summary = summary,
            chapterCount = chapters.size,
            source = recipe.displayName,
            readingUrl = readingUrl,
            chapters = chapters
        )
    }

    fun extractChapterContent(
        recipe: CustomSourceRecipeDefinition,
        document: Document,
        pageUrl: String
    ): ContentResult {
        val title = document.title().trim().ifBlank { null }
        val elements = when (recipe.contentKind) {
            CustomSourceContentKind.NOVEL -> extractTextElements(recipe, document)
            CustomSourceContentKind.IMAGE_SERIES -> extractImageElements(recipe, document, pageUrl)
        }

        if (elements.isEmpty()) {
            return ContentResult.Error("Recipe did not extract any content for $pageUrl")
        }

        return ContentResult.Success(
            elements = elements,
            title = title,
            url = pageUrl,
            textCount = elements.count { it is ContentElement.Text },
            imageCount = elements.count { it.isImage() }
        )
    }

    private fun extractChapters(
        recipe: CustomSourceRecipeDefinition,
        document: Document,
        pageUrl: String
    ): List<ChapterInfo> {
        val linkSelector = recipe.chapterLinkSelector?.ifBlank { null }
        val titleSelector = recipe.chapterTitleSelector?.ifBlank { null }

        val chapters = document.select(recipe.chapterItemSelector).mapNotNull { item ->
            val link = when {
                item.tagName().equals("a", ignoreCase = true) -> item
                !linkSelector.isNullOrBlank() -> item.select(linkSelector).firstOrNull()
                else -> item.select("a").firstOrNull()
            } ?: return@mapNotNull null

            val url = link.absoluteHref(pageUrl)
            if (url.isBlank()) return@mapNotNull null

            val title = titleSelector
                ?.let { selector -> item.select(selector).firstOrNull()?.text()?.trim() }
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()

            if (title.isBlank()) return@mapNotNull null

            ChapterInfo(
                title = title,
                url = url,
                number = TextUtils.extractChapterNumber(title) ?: TextUtils.extractChapterNumber(url)
            )
        }.distinctBy { it.url }

        if (chapters.isEmpty()) {
            throw IllegalStateException("Recipe did not extract any chapters for $pageUrl")
        }

        return when (recipe.chapterOrder) {
            CustomSourceChapterOrder.ASCENDING -> chapters
            CustomSourceChapterOrder.DESCENDING -> chapters.reversed()
        }
    }

    private fun extractTextElements(recipe: CustomSourceRecipeDefinition, document: Document): List<ContentElement> {
        val selector = recipe.textContentSelector?.ifBlank { null }
            ?: throw IllegalStateException("Text recipe missing textContentSelector")

        val joined = document.select(selector)
            .mapNotNull { element ->
                extractTextPreservingLineBreaks(element).trim().takeIf { it.isNotBlank() }
            }
            .joinToString("\n\n")

        return TextUtils.formatChapterText(joined)
            .split(DOUBLE_NEWLINE_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map(ContentElement::Text)
    }

    private fun extractImageElements(
        recipe: CustomSourceRecipeDefinition,
        document: Document,
        pageUrl: String
    ): List<ContentElement> {
        val selector = recipe.imageContentSelector?.ifBlank { null }
            ?: throw IllegalStateException("Image recipe missing imageContentSelector")

        return document.select(selector)
            .mapNotNull { image ->
                val absoluteUrl = image.findImageUrl(pageUrl)
                if (absoluteUrl.isBlank()) {
                    null
                } else {
                    ContentElement.Image(url = absoluteUrl)
                }
            }
    }

    private fun extractTextPreservingLineBreaks(element: Element): String {
        if (element.selectFirst("br") == null) return element.text()

        val builder = StringBuilder()
        element.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> builder.append(node.text())
                    is Element -> if (node.tagName() == "br") builder.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) = Unit
        })
        return builder.toString()
    }

    private fun Element.absoluteHref(pageUrl: String): String {
        val href = attr("href")
        return resolveUrl(pageUrl, href)
    }

    private fun Element.findImageUrl(pageUrl: String): String {
        val value = listOf("data-src", "data-original", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attrName -> attr(attrName).takeIf { it.isNotBlank() } }
            ?: return ""
        return resolveUrl(pageUrl, value)
    }

    private fun resolveUrl(baseUrl: String, target: String): String {
        if (target.isBlank()) return ""
        return runCatching { URI(baseUrl).resolve(target).toString() }.getOrDefault(target)
    }
}
