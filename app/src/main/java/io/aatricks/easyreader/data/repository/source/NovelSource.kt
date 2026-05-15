package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.model.ExploreItem

enum class BrowseMode(val label: String) {
    POPULAR("Popular"),
    LATEST("Latest"),
    NEW("New")
}

interface NovelSource {
    val name: String
    val baseUrl: String

    /**
     * Parser version. Bump whenever the source's HTML structure changes in a way that
     * required code changes here. Surfaced in logs so a deployed app can be cross-checked
     * against the version reporters expect, and reserved as the hook for a future remote
     * kill-switch ("only enable v >= X").
     */
    val version: String

    suspend fun getPopularNovels(page: Int = 1, tags: List<String> = emptyList()): List<ExploreItem>

    /**
     * Browse mode aware fetch. Default falls back to popular for sources that don't differentiate.
     */
    suspend fun getNovels(
        mode: BrowseMode,
        page: Int = 1,
        tags: List<String> = emptyList()
    ): List<ExploreItem> = getPopularNovels(page, tags)

    suspend fun searchNovels(query: String, page: Int = 1): List<ExploreItem>
    suspend fun getNovelDetails(url: String): ExploreItem
    suspend fun getTags(): List<String> = emptyList()
}
