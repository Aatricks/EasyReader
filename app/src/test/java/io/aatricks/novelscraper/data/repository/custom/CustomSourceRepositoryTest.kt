package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.local.CustomSourceRecipeDao
import io.aatricks.novelscraper.data.model.AiSourceSetupPreview
import io.aatricks.novelscraper.data.model.CustomSourceChapterOrder
import io.aatricks.novelscraper.data.model.CustomSourceContentKind
import io.aatricks.novelscraper.data.model.CustomSourceRecipe
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CustomSourceRepositoryTest {

    private val dao: CustomSourceRecipeDao = mock()
    private val aiSourceSetupService: AiSourceSetupService = mock()
    private val pageFetcher: RecipePageFetcher = mock()
    private lateinit var repository: CustomSourceRepository

    @Before
    fun setup() {
        repository = CustomSourceRepository(
            recipeDao = dao,
            aiSourceSetupService = aiSourceSetupService,
            pageFetcher = pageFetcher,
            recipeEngine = CustomSourceRecipeEngine()
        )
    }

    @Test
    fun `saveRecipe reuses existing recipe id for same base url`() = runTest {
        val recipeDefinition = CustomSourceRecipeDefinition(
            displayName = "Example Source",
            baseNovelUrl = "https://example.com/series/the-great-story",
            contentKind = CustomSourceContentKind.NOVEL,
            titleSelector = ".series-title",
            chapterItemSelector = ".chapter-list li",
            chapterOrder = CustomSourceChapterOrder.DESCENDING,
            textContentSelector = ".chapter-content p"
        )
        val existing = CustomSourceRecipe(
            id = "recipe-1",
            displayName = "Old Name",
            baseNovelUrl = recipeDefinition.baseNovelUrl,
            contentKind = CustomSourceContentKind.NOVEL,
            recipeJson = Json.encodeToString(CustomSourceRecipeDefinition.serializer(), recipeDefinition),
            createdAt = 1L,
            updatedAt = 1L,
            lastValidatedAt = 1L
        )
        val preview = AiSourceSetupPreview(
            displayName = "Example Source",
            title = "The Great Story",
            baseNovelUrl = recipeDefinition.baseNovelUrl,
            firstChapterUrl = "https://example.com/series/the-great-story/chapter-1",
            firstChapterTitle = "Chapter 1",
            chapterCount = 12,
            contentKind = CustomSourceContentKind.NOVEL,
            recipe = recipeDefinition
        )

        whenever(dao.getByBaseNovelUrl(recipeDefinition.baseNovelUrl)).thenReturn(existing)

        val saved = repository.saveRecipe(preview)

        assertEquals(existing.id, saved.id)
        verify(dao).insert(
            check {
                assertEquals(existing.id, it.id)
                assertEquals("Example Source", it.displayName)
                assertEquals(existing.createdAt, it.createdAt)
            }
        )
    }

    @Test
    fun `getNovelDetails executes stored recipe against fetched html`() = runTest {
        val baseUrl = "https://example.com/series/the-great-story"
        val html = """
            <html>
            <body>
                <h1 class="series-title">The Great Story</h1>
                <ul class="chapter-list">
                    <li><a href="/series/the-great-story/chapter-2">Chapter 2</a></li>
                    <li><a href="/series/the-great-story/chapter-1">Chapter 1</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        val recipeDefinition = CustomSourceRecipeDefinition(
            displayName = "Example Source",
            baseNovelUrl = baseUrl,
            contentKind = CustomSourceContentKind.NOVEL,
            titleSelector = ".series-title",
            chapterItemSelector = ".chapter-list li",
            chapterOrder = CustomSourceChapterOrder.DESCENDING,
            textContentSelector = ".chapter-content p"
        )
        val recipe = CustomSourceRecipe(
            id = "recipe-1",
            displayName = "Example Source",
            baseNovelUrl = baseUrl,
            contentKind = CustomSourceContentKind.NOVEL,
            recipeJson = Json.encodeToString(CustomSourceRecipeDefinition.serializer(), recipeDefinition),
            createdAt = 1L,
            updatedAt = 1L,
            lastValidatedAt = 1L
        )

        whenever(dao.getById("recipe-1")).thenReturn(recipe)
        whenever(pageFetcher.fetch(baseUrl, null)).thenReturn(FetchedHtmlPage(baseUrl, baseUrl, html))

        val details = repository.getNovelDetails("recipe-1")

        assertEquals("The Great Story", details?.title)
        assertEquals(2, details?.chapters?.size)
        assertEquals("https://example.com/series/the-great-story/chapter-1", details?.chapters?.firstOrNull()?.url)
    }
}
