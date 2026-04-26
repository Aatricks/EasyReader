package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.model.ExploreItem

interface NovelSource {
    val name: String
    val baseUrl: String
    suspend fun getPopularNovels(page: Int = 1, tags: List<String> = emptyList()): List<ExploreItem>
    suspend fun searchNovels(query: String, page: Int = 1): List<ExploreItem>
    suspend fun getNovelDetails(url: String): ExploreItem
    suspend fun getTags(): List<String> = emptyList()
}
