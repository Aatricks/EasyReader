package io.aatricks.novelscraper.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class CustomSourceContentKind {
    NOVEL,
    IMAGE_SERIES
}

@Serializable
enum class CustomSourceChapterOrder {
    ASCENDING,
    DESCENDING
}

@Serializable
data class CustomSourceRecipeDefinition(
    val schemaVersion: Int = 1,
    val displayName: String,
    val baseNovelUrl: String,
    val contentKind: CustomSourceContentKind,
    val titleSelector: String,
    val chapterItemSelector: String,
    val chapterLinkSelector: String? = null,
    val chapterTitleSelector: String? = null,
    val readingUrlSelector: String? = null,
    val chapterOrder: CustomSourceChapterOrder = CustomSourceChapterOrder.DESCENDING,
    val textContentSelector: String? = null,
    val imageContentSelector: String? = null,
    val summarySelector: String? = null,
    val coverSelector: String? = null
)

@Entity(tableName = "custom_source_recipes")
data class CustomSourceRecipe(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val baseNovelUrl: String,
    val contentKind: CustomSourceContentKind,
    val recipeJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastValidatedAt: Long
)
