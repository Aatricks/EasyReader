package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.model.CustomSourceChapterOrder
import io.aatricks.novelscraper.data.model.CustomSourceContentKind
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSourceSetupServiceTest {

    @Test
    fun `generatePreview validates inferred recipe before returning preview`() = runTest {
        val seriesUrl = "https://example.com/series/the-great-story"
        val chapterUrl = "https://example.com/series/the-great-story/chapter-1"
        val seriesHtml = """
            <html>
            <body>
                <h1 class="series-title">The Great Story</h1>
                <div class="summary">A fast moving fantasy story.</div>
                <ul class="chapter-list">
                    <li><a href="/series/the-great-story/chapter-2">Chapter 2</a></li>
                    <li><a href="/series/the-great-story/chapter-1">Chapter 1</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        val chapterHtml = """
            <html>
            <body>
                <div class="chapter-content">
                    <p>First paragraph.</p>
                    <p>Second paragraph.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        val fetcher = object : RecipePageFetcher {
            override suspend fun fetch(url: String, referer: String?): FetchedHtmlPage {
                return when (url) {
                    "https://example.com/unsupported" -> FetchedHtmlPage(url, url, "<html><body>Seed</body></html>")
                    seriesUrl -> FetchedHtmlPage(url, url, seriesHtml)
                    chapterUrl -> FetchedHtmlPage(url, url, chapterHtml)
                    else -> error("Unexpected url: $url")
                }
            }
        }
        val generator = object : AiTextGenerator {
            override suspend fun generate(systemPrompt: String, prompt: String, maxTokens: Int): Result<String> {
                val recipe = CustomSourceRecipeDefinition(
                    displayName = "Example Source",
                    baseNovelUrl = seriesUrl,
                    contentKind = CustomSourceContentKind.NOVEL,
                    titleSelector = ".series-title",
                    chapterItemSelector = ".chapter-list li",
                    chapterLinkSelector = "a",
                    chapterOrder = CustomSourceChapterOrder.DESCENDING,
                    textContentSelector = ".chapter-content p",
                    summarySelector = ".summary"
                )
                return Result.success(
                    kotlinx.serialization.json.Json.encodeToString(
                        CustomSourceRecipeDefinition.serializer(),
                        recipe
                    )
                )
            }
        }

        val service = AiSourceSetupService(
            pageFetcher = fetcher,
            textGenerator = generator,
            recipeEngine = CustomSourceRecipeEngine()
        )

        val preview = service.generatePreview("https://example.com/unsupported").getOrThrow()

        assertEquals("Example Source", preview.displayName)
        assertEquals("The Great Story", preview.title)
        assertEquals(seriesUrl, preview.baseNovelUrl)
        assertEquals(chapterUrl, preview.firstChapterUrl)
        assertEquals("Chapter 1", preview.firstChapterTitle)
        assertEquals(2, preview.chapterCount)
        assertEquals(CustomSourceContentKind.NOVEL, preview.contentKind)
    }

    @Test
    fun `generatePreview falls back to heuristic recipe when model output is underspecified`() = runTest {
        val seriesUrl = "https://asurascans.org/manga/solo-leveling"
        val chapterUrl = "https://asurascans.org/manga/solo-leveling/chapter-1"
        val seriesHtml = """
            <html>
            <body>
                <h1>Solo Leveling</h1>
                <div class="summary">A hunter levels up alone.</div>
                <div class="chapters">
                    <a href="/manga/solo-leveling/chapter-3">Chapter 3</a>
                    <a href="/manga/solo-leveling/chapter-2">Chapter 2</a>
                    <a href="/manga/solo-leveling/chapter-1">Chapter 1</a>
                </div>
            </body>
            </html>
        """.trimIndent()
        val chapterHtml = """
            <html>
            <body>
                <main class="chapter-main">
                    <img src="/images/page-1.jpg" />
                    <img src="/images/page-2.jpg" />
                    <img src="/images/page-3.jpg" />
                    <img src="/images/page-4.jpg" />
                </main>
            </body>
            </html>
        """.trimIndent()

        val fetcher = object : RecipePageFetcher {
            override suspend fun fetch(url: String, referer: String?): FetchedHtmlPage {
                return when (url) {
                    seriesUrl -> FetchedHtmlPage(url, url, seriesHtml)
                    chapterUrl -> FetchedHtmlPage(url, url, chapterHtml)
                    else -> error("Unexpected url: $url")
                }
            }
        }
        val generator = object : AiTextGenerator {
            override suspend fun generate(systemPrompt: String, prompt: String, maxTokens: Int): Result<String> {
                return Result.success("{}")
            }
        }

        val service = AiSourceSetupService(
            pageFetcher = fetcher,
            textGenerator = generator,
            recipeEngine = CustomSourceRecipeEngine()
        )

        val preview = service.generatePreview(seriesUrl).getOrThrow()

        assertEquals("Solo Leveling", preview.title)
        assertEquals(seriesUrl, preview.baseNovelUrl)
        assertEquals(chapterUrl, preview.firstChapterUrl)
        assertEquals(CustomSourceContentKind.IMAGE_SERIES, preview.contentKind)
        assertTrue(preview.recipe.chapterItemSelector.contains("chapter"))
        assertTrue(preview.recipe.imageContentSelector?.contains("img") == true)
    }
}
