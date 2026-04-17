package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.CustomSourceChapterOrder
import io.aatricks.novelscraper.data.model.CustomSourceContentKind
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourceRecipeEngineTest {

    private val engine = CustomSourceRecipeEngine()

    @Test
    fun `extractSeriesDetails normalizes descending chapter list and resolves metadata`() {
        val baseUrl = "https://example.com/series/the-great-story"
        val html = """
            <html>
            <body>
                <h1 class="series-title">The Great Story</h1>
                <div class="summary">A fast moving fantasy story.</div>
                <img class="cover" src="/assets/cover.jpg" />
                <ul class="chapter-list">
                    <li><a href="/series/the-great-story/chapter-2">Chapter 2</a></li>
                    <li><a href="/series/the-great-story/chapter-1">Chapter 1</a></li>
                </ul>
                <a class="start-reading" href="/series/the-great-story/chapter-1">Read now</a>
            </body>
            </html>
        """.trimIndent()
        val recipe = CustomSourceRecipeDefinition(
            displayName = "Example Source",
            baseNovelUrl = baseUrl,
            contentKind = CustomSourceContentKind.NOVEL,
            titleSelector = ".series-title",
            chapterItemSelector = ".chapter-list li",
            chapterLinkSelector = "a",
            chapterOrder = CustomSourceChapterOrder.DESCENDING,
            readingUrlSelector = ".start-reading",
            summarySelector = ".summary",
            coverSelector = ".cover",
            textContentSelector = ".chapter-content p"
        )

        val details = engine.extractSeriesDetails(recipe, Jsoup.parse(html, baseUrl), baseUrl)

        assertEquals("The Great Story", details.title)
        assertEquals("A fast moving fantasy story.", details.summary)
        assertEquals("https://example.com/assets/cover.jpg", details.coverUrl)
        assertEquals(2, details.chapters.size)
        assertEquals("https://example.com/series/the-great-story/chapter-1", details.chapters.first().url)
        assertEquals("https://example.com/series/the-great-story/chapter-1", details.readingUrl)
        assertEquals("Example Source", details.source)
    }

    @Test
    fun `extractChapterContent returns formatted text elements for novel recipes`() {
        val pageUrl = "https://example.com/series/the-great-story/chapter-1"
        val html = """
            <html>
            <head><title>Chapter 1</title></head>
            <body>
                <div class="chapter-content">
                    <p>First paragraph.</p>
                    <p>Second paragraph.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        val recipe = CustomSourceRecipeDefinition(
            displayName = "Example Source",
            baseNovelUrl = "https://example.com/series/the-great-story",
            contentKind = CustomSourceContentKind.NOVEL,
            titleSelector = ".series-title",
            chapterItemSelector = ".chapter-list li",
            textContentSelector = ".chapter-content p"
        )

        val result = engine.extractChapterContent(recipe, Jsoup.parse(html, pageUrl), pageUrl)

        val success = result as ContentResult.Success
        assertEquals("Chapter 1", success.title)
        assertEquals(2, success.elements.size)
    }

    @Test
    fun `extractChapterContent returns image elements for image series recipes`() {
        val pageUrl = "https://example.com/series/the-great-story/chapter-1"
        val html = """
            <html>
            <body>
                <div class="reader">
                    <img data-src="/images/page-1.jpg" />
                    <img src="/images/page-2.jpg" />
                </div>
            </body>
            </html>
        """.trimIndent()
        val recipe = CustomSourceRecipeDefinition(
            displayName = "Example Source",
            baseNovelUrl = "https://example.com/series/the-great-story",
            contentKind = CustomSourceContentKind.IMAGE_SERIES,
            titleSelector = ".series-title",
            chapterItemSelector = ".chapter-list li",
            imageContentSelector = ".reader img"
        )

        val result = engine.extractChapterContent(recipe, Jsoup.parse(html, pageUrl), pageUrl)

        val success = result as ContentResult.Success
        assertEquals(2, success.elements.size)
        assertTrue(success.elements.all { it.isImage() })
    }
}
