package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.NovelSource
import io.aatricks.novelscraper.data.repository.source.StandardEbooksSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ExploreRepository {

    private val sources: List<NovelSource> = listOf(
        NovelFireSource(),
        StandardEbooksSource()
    )

    suspend fun getPopularNovels(page: Int = 1): List<ExploreItem> = coroutineScope {
        sources.map { source ->
            async {
                try {
                    source.getPopularNovels(page)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().shuffled() // Shuffle to mix sources
    }

    suspend fun searchNovels(query: String, page: Int = 1): List<ExploreItem> = coroutineScope {
        sources.map { source ->
            async {
                try {
                    source.searchNovels(query, page)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    suspend fun getNovelDetails(url: String, sourceName: String): ExploreItem? {
        val source = sources.find { it.name == sourceName } ?: return null
        return try {
            source.getNovelDetails(url)
        } catch (e: Exception) {
            null
        }
    }

    fun getSourceNames(): List<String> = sources.map { it.name }
}
