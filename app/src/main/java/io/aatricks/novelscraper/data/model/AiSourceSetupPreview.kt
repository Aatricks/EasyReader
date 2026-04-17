package io.aatricks.novelscraper.data.model

data class AiSourceSetupPreview(
    val displayName: String,
    val title: String,
    val baseNovelUrl: String,
    val firstChapterUrl: String,
    val firstChapterTitle: String,
    val chapterCount: Int,
    val contentKind: CustomSourceContentKind,
    val recipe: CustomSourceRecipeDefinition
)
