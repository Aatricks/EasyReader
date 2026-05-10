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
