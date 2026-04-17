package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.model.AiSourceSetupPreview
import io.aatricks.novelscraper.data.model.CustomSourceChapterOrder
import io.aatricks.novelscraper.data.model.CustomSourceContentKind
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSourceSetupService @Inject constructor(
    private val pageFetcher: RecipePageFetcher,
    private val textGenerator: AiTextGenerator,
    private val recipeEngine: CustomSourceRecipeEngine
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generatePreview(url: String): Result<AiSourceSetupPreview> = runCatching {
        val seedPage = pageFetcher.fetch(url)
        val seedDocument = Jsoup.parse(seedPage.html, seedPage.resolvedUrl)
        val generatedPayload = textGenerator.generate(
            systemPrompt = SYSTEM_PROMPT,
            prompt = buildPrompt(seedPage),
            maxTokens = 768
        ).getOrNull()
        val generatedObject = generatedPayload
            ?.let { runCatching { extractFirstJsonObject(it) }.getOrNull() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

        val seedRecipe = inferSeriesRecipe(seedDocument, seedPage.resolvedUrl)
        val seriesUrl = generatedObject?.string("baseNovelUrl")?.takeIf { it.isNotBlank() } ?: seedRecipe.baseNovelUrl

        val seriesPage = if (seriesUrl == seedPage.resolvedUrl) {
            seedPage
        } else {
            pageFetcher.fetch(seriesUrl, referer = seedPage.resolvedUrl)
        }
        val seriesDocument = Jsoup.parse(seriesPage.html, seriesPage.resolvedUrl)
        val seriesRecipe = inferSeriesRecipe(seriesDocument, seriesPage.resolvedUrl)
        val preliminaryRecipe = mergeRecipe(
            generated = generatedObject,
            fallback = seriesRecipe
        )

        val details = recipeEngine.extractSeriesDetails(preliminaryRecipe, seriesDocument, seriesPage.resolvedUrl)
        val firstChapter = details.chapters.firstOrNull()
            ?: throw IllegalStateException("Recipe did not produce any chapters")
        val chapterPage = pageFetcher.fetch(firstChapter.url, referer = seriesPage.resolvedUrl)
        val chapterDocument = Jsoup.parse(chapterPage.html, chapterPage.resolvedUrl)
        val chapterRecipe = inferContentRecipe(preliminaryRecipe, chapterDocument)
        val recipe = mergeRecipe(
            generated = generatedObject,
            fallback = chapterRecipe
        )
        recipeEngine.extractChapterContent(recipe, chapterDocument, chapterPage.resolvedUrl)

        AiSourceSetupPreview(
            displayName = recipe.displayName.ifBlank { extractHost(seriesPage.resolvedUrl) },
            title = details.title,
            baseNovelUrl = recipe.baseNovelUrl.ifBlank { seriesPage.resolvedUrl },
            firstChapterUrl = firstChapter.url,
            firstChapterTitle = firstChapter.title,
            chapterCount = details.chapters.size,
            contentKind = recipe.contentKind,
            recipe = recipe
        )
    }

    private fun buildPrompt(page: FetchedHtmlPage): String {
        val document = Jsoup.parse(page.html, page.resolvedUrl)
        val title = document.title()
        val links = document.select("a[href]").take(30).joinToString("\n") { link ->
            "- ${link.text().trim().ifBlank { "(no text)" }} => ${link.attr("href")}"
        }
        val paragraphs = document.select("p, h1, h2, h3").take(20).joinToString("\n") { element ->
            element.text().trim()
        }

        return buildString {
            appendLine("Infer a single-series extraction recipe for this page.")
            appendLine("Seed URL: ${page.resolvedUrl}")
            appendLine("HTML title: $title")
            appendLine("Visible text:")
            appendLine(paragraphs)
            appendLine()
            appendLine("Representative links:")
            appendLine(links)
            appendLine()
            appendLine("Return JSON only using this shape:")
            appendLine("""{"displayName":"","baseNovelUrl":"","contentKind":"NOVEL|IMAGE_SERIES","titleSelector":"","chapterItemSelector":"","chapterLinkSelector":null,"chapterTitleSelector":null,"readingUrlSelector":null,"chapterOrder":"ASCENDING|DESCENDING","textContentSelector":null,"imageContentSelector":null,"summarySelector":null,"coverSelector":null}""")
        }
    }

    private fun extractFirstJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        if (start == -1) throw IllegalArgumentException("No JSON object found in model output")

        var depth = 0
        var inString = false
        var escape = false
        for (index in start until raw.length) {
            val char = raw[index]
            when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) {
                        return raw.substring(start, index + 1)
                    }
                }
            }
        }

        throw IllegalArgumentException("Incomplete JSON object in model output")
    }

    private fun extractHost(url: String): String {
        return runCatching { java.net.URI(url).host ?: "Custom Source" }.getOrDefault("Custom Source")
    }

    private fun mergeRecipe(
        generated: JsonObject?,
        fallback: CustomSourceRecipeDefinition
    ): CustomSourceRecipeDefinition {
        if (generated == null) return fallback

        val generatedKind = generated.string("contentKind")
            ?.let { runCatching { CustomSourceContentKind.valueOf(it) }.getOrNull() }
        val generatedOrder = generated.string("chapterOrder")
            ?.let { runCatching { CustomSourceChapterOrder.valueOf(it) }.getOrNull() }

        return fallback.copy(
            displayName = generated.string("displayName") ?: fallback.displayName,
            baseNovelUrl = generated.string("baseNovelUrl") ?: fallback.baseNovelUrl,
            contentKind = generatedKind ?: fallback.contentKind,
            titleSelector = generated.string("titleSelector") ?: fallback.titleSelector,
            chapterItemSelector = generated.string("chapterItemSelector") ?: fallback.chapterItemSelector,
            chapterLinkSelector = generated.string("chapterLinkSelector") ?: fallback.chapterLinkSelector,
            chapterTitleSelector = generated.string("chapterTitleSelector") ?: fallback.chapterTitleSelector,
            readingUrlSelector = generated.string("readingUrlSelector") ?: fallback.readingUrlSelector,
            chapterOrder = generatedOrder ?: fallback.chapterOrder,
            textContentSelector = generated.string("textContentSelector") ?: fallback.textContentSelector,
            imageContentSelector = generated.string("imageContentSelector") ?: fallback.imageContentSelector,
            summarySelector = generated.string("summarySelector") ?: fallback.summarySelector,
            coverSelector = generated.string("coverSelector") ?: fallback.coverSelector
        )
    }

    private fun inferSeriesRecipe(document: Document, resolvedUrl: String): CustomSourceRecipeDefinition {
        val titleSelector = firstMatchingSelector(
            document,
            listOf("h1", ".entry-title", ".post-title", ".series-title", ".manga-title", ".title h1")
        ) ?: "h1"

        val chapterSelector = firstSelectorWithChapterLinks(
            document,
            listOf(
                ".wp-manga-chapter > a",
                ".wp-manga-chapter a",
                ".chapter-list a",
                ".chapters a",
                ".listing-chapters_wrap a",
                ".chapter-item a",
                "a[href*='chapter']",
                "a[href*='chap']",
                "a[href*='episode']"
            ),
            resolvedUrl
        ) ?: "a[href*='chapter'], a[href*='chap'], a[href*='episode']"

        val chapterOrder = inferChapterOrder(document, chapterSelector, resolvedUrl)
        val summarySelector = firstTextSelector(
            document,
            listOf(".summary", ".description", ".entry-content", ".post-content", ".series-summary")
        )
        val coverSelector = firstImageSelector(
            document,
            listOf(".summary_image img", ".cover img", ".series-thumb img", ".post-thumb img", "img")
        )

        return CustomSourceRecipeDefinition(
            displayName = extractDisplayName(resolvedUrl),
            baseNovelUrl = resolvedUrl,
            contentKind = CustomSourceContentKind.NOVEL,
            titleSelector = titleSelector,
            chapterItemSelector = chapterSelector,
            chapterOrder = chapterOrder,
            summarySelector = summarySelector,
            coverSelector = coverSelector
        )
    }

    private fun inferContentRecipe(
        baseRecipe: CustomSourceRecipeDefinition,
        chapterDocument: Document
    ): CustomSourceRecipeDefinition {
        val imageSelector = firstImageSelector(
            chapterDocument,
            listOf(
                ".reading-content img",
                ".entry-content img",
                ".chapter-content img",
                ".reader img",
                ".container-chapter-reader img",
                ".page-break img",
                "main img",
                "article img"
            )
        )
        val imageCount = imageSelector?.let { chapterDocument.select(it).size } ?: 0

        val textSelector = firstTextSelector(
            chapterDocument,
            listOf(
                ".text-left p",
                ".entry-content p",
                ".chapter-content p",
                ".reading-content p",
                "article p",
                "main p"
            )
        )
        val textCount = textSelector?.let { chapterDocument.select(it).size } ?: 0

        return if (imageCount >= 3 && imageCount >= textCount) {
            baseRecipe.copy(
                contentKind = CustomSourceContentKind.IMAGE_SERIES,
                imageContentSelector = imageSelector,
                textContentSelector = null
            )
        } else {
            baseRecipe.copy(
                contentKind = CustomSourceContentKind.NOVEL,
                textContentSelector = textSelector ?: "article p, main p",
                imageContentSelector = null
            )
        }
    }

    private fun firstMatchingSelector(document: Document, selectors: List<String>): String? {
        return selectors.firstOrNull { selector -> document.select(selector).firstOrNull()?.text()?.isNotBlank() == true }
    }

    private fun firstSelectorWithChapterLinks(document: Document, selectors: List<String>, baseUrl: String): String? {
        return selectors.firstOrNull { selector ->
            document.select(selector).count { link -> isLikelyChapterLink(link, baseUrl) } >= 2
        }
    }

    private fun inferChapterOrder(document: Document, selector: String, baseUrl: String): CustomSourceChapterOrder {
        val chapterNumbers = document.select(selector)
            .filter { link -> isLikelyChapterLink(link, baseUrl) }
            .mapNotNull { link ->
                io.aatricks.novelscraper.util.TextUtils.extractChapterNumber(link.text())
                    ?: io.aatricks.novelscraper.util.TextUtils.extractChapterNumber(link.attr("href"))
            }

        if (chapterNumbers.size < 2) return CustomSourceChapterOrder.DESCENDING
        return if (chapterNumbers.first() > chapterNumbers.last()) {
            CustomSourceChapterOrder.DESCENDING
        } else {
            CustomSourceChapterOrder.ASCENDING
        }
    }

    private fun firstTextSelector(document: Document, selectors: List<String>): String? {
        return selectors.firstOrNull { selector ->
            document.select(selector).any { it.text().trim().length >= 20 }
        }
    }

    private fun firstImageSelector(document: Document, selectors: List<String>): String? {
        return selectors.firstOrNull { selector ->
            document.select(selector).any { it.attr("src").isNotBlank() || it.attr("data-src").isNotBlank() }
        }
    }

    private fun isLikelyChapterLink(link: Element, baseUrl: String): Boolean {
        val href = runCatching { URI(baseUrl).resolve(link.attr("href")).toString() }.getOrDefault(link.attr("href"))
        val text = link.text()
        if (href.isBlank()) return false
        return href.contains("chapter", ignoreCase = true) ||
            href.contains("chap", ignoreCase = true) ||
            href.contains("episode", ignoreCase = true) ||
            text.contains("chapter", ignoreCase = true) ||
            text.contains("episode", ignoreCase = true)
    }

    private fun extractDisplayName(url: String): String {
        val host = extractHost(url).removePrefix("www.")
        val base = host.substringBefore('.').replace('-', ' ').replace('_', ' ')
        return base.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .ifBlank { "Custom Source" }
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val SYSTEM_PROMPT = """
You infer CSS-selector based extraction recipes for one manga, manhwa, or novel series.
Return JSON only, with fields matching the app schema exactly.
Use CSS selectors only.
Set chapterOrder to ASCENDING if the page already lists oldest chapter first, otherwise DESCENDING.
For novels set textContentSelector.
For manga/manhwa set imageContentSelector.
"""
    }
}
