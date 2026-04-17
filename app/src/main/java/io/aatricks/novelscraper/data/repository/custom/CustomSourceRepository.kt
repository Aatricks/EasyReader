package io.aatricks.novelscraper.data.repository.custom

import io.aatricks.novelscraper.data.local.CustomSourceRecipeDao
import io.aatricks.novelscraper.data.model.AiSourceSetupPreview
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.CustomSourceRecipe
import io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition
import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomSourceRepository @Inject constructor(
    private val recipeDao: CustomSourceRecipeDao,
    private val aiSourceSetupService: AiSourceSetupService,
    private val pageFetcher: RecipePageFetcher,
    private val recipeEngine: CustomSourceRecipeEngine
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateSetupPreview(url: String): Result<AiSourceSetupPreview> {
        return aiSourceSetupService.generatePreview(url)
    }

    suspend fun saveRecipe(preview: AiSourceSetupPreview): CustomSourceRecipe = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = recipeDao.getByBaseNovelUrl(preview.baseNovelUrl)
        val entity = CustomSourceRecipe(
            id = existing?.id ?: UUID.randomUUID().toString(),
            displayName = preview.displayName,
            baseNovelUrl = preview.baseNovelUrl,
            contentKind = preview.contentKind,
            recipeJson = json.encodeToString(CustomSourceRecipeDefinition.serializer(), preview.recipe),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastValidatedAt = now
        )
        recipeDao.insert(entity)
        entity
    }

    suspend fun getRecipe(id: String): CustomSourceRecipe? = withContext(Dispatchers.IO) {
        recipeDao.getById(id)
    }

    suspend fun getNovelDetails(recipeId: String): ExploreItem? = withContext(Dispatchers.IO) {
        val recipeEntity = recipeDao.getById(recipeId) ?: return@withContext null
        val recipe = decodeRecipe(recipeEntity)
        runCatching {
            val page = pageFetcher.fetch(recipe.baseNovelUrl, null)
            val details = recipeEngine.extractSeriesDetails(recipe, Jsoup.parse(page.html, page.resolvedUrl), page.resolvedUrl)
            details.copy(source = recipeEntity.displayName, url = recipeEntity.baseNovelUrl)
        }.getOrNull()
    }

    suspend fun loadContent(recipeId: String, url: String): ContentResult = withContext(Dispatchers.IO) {
        val recipeEntity = recipeDao.getById(recipeId)
            ?: return@withContext ContentResult.Error("Unknown custom recipe: $recipeId")
        val recipe = decodeRecipe(recipeEntity)
        runCatching {
            val page = pageFetcher.fetch(url, recipe.baseNovelUrl)
            recipeEngine.extractChapterContent(recipe, Jsoup.parse(page.html, page.resolvedUrl), page.resolvedUrl)
        }.getOrElse { error ->
            ContentResult.Error("Failed to load custom source content: ${error.message}", error as? Exception)
        }
    }

    private fun decodeRecipe(entity: CustomSourceRecipe): CustomSourceRecipeDefinition {
        val definition = json.decodeFromString(CustomSourceRecipeDefinition.serializer(), entity.recipeJson)
        return definition.copy(
            displayName = entity.displayName,
            baseNovelUrl = entity.baseNovelUrl,
            contentKind = entity.contentKind
        )
    }
}
