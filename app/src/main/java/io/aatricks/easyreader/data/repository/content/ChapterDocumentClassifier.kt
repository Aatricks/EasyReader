package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import org.jsoup.nodes.Document

object ChapterDocumentClassifier {

    fun detectMangaReaderHints(document: Document): Boolean {
        val selector = listOf(
            ".container-chapter-reader",
            ".reader-content",
            ".chapter-content",
            ".chapter-img",
            ".read-content",
            ".container-reading",
            ".vung-doc",
            "div.page-break",
            "img[data-page-index]",
            "div[data-page]",
            "[class*=\"chapter-reader\"]",
            "[class*=\"manga-reader\"]"
        ).joinToString(", ")
        val raw = document.html()
        return document.selectFirst(selector) != null ||
            (raw.contains("\"pages\"") && raw.contains("\"url\"") && raw.contains("/chapters/")) ||
            (raw.contains("chapterImages") && raw.contains("[\""))
    }

    fun isRenderableTextChapter(document: Document, elements: List<ContentElement>): Boolean {
        val hasNoMangaHints = !detectMangaReaderHints(document)
        val hasNoImageTags = document.select("img[src], image[href], image[xlink|href], source[srcset]").isEmpty()
        return hasNoMangaHints && hasNoImageTags && hasTextElement(elements)
    }

    private fun hasTextElement(elements: List<ContentElement>): Boolean {
        return elements.any { element ->
            when (element) {
                is ContentElement.Text -> element.content.isNotBlank()
                is ContentElement.PageContent -> hasTextElement(element.elements)
                else -> false
            }
        }
    }
}
