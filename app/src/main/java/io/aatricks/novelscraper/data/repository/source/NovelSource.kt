package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem

interface NovelSource {
    val name: String
    val baseUrl: String
    suspend fun getPopularNovels(page: Int = 1): List<ExploreItem>
    suspend fun searchNovels(query: String, page: Int = 1): List<ExploreItem>
    suspend fun getNovelDetails(url: String): ExploreItem
}
