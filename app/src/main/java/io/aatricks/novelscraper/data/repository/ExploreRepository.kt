package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.source.MangaBatSource
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.NovelSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for exploring and searching novels from various sources.
 */
@Singleton
class ExploreRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    private val staticSources: List<NovelSource> = listOf(
        NovelFireSource(),
        MangaBatSource()
    )

    private val sources: List<NovelSource>
        get() = staticSources

    fun getAllSources(): List<NovelSource> = sources

    suspend fun getPopularNovels(page: Int = 1, sourceName: String? = null, tags: List<String> = emptyList()): List<ExploreItem> = coroutineScope {
        val activeSources = if (sourceName == null) sources else sources.filter { it.name == sourceName }
        
        activeSources.map { source ->
            async {
                try {
                    source.getPopularNovels(page, tags)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().let { if (sourceName == null) it.shuffled() else it }
    }
    
    suspend fun getTags(sourceName: String?): List<String> = coroutineScope {
        if (sourceName != null) {
            sources.find { it.name == sourceName }?.getTags() ?: emptyList()
        } else {
            // Aggregate tags from all sources and deduplicate
            sources.map { async { it.getTags() } }.awaitAll().flatten().distinct().sorted()
        }
    }
    
    suspend fun searchNovels(query: String, page: Int = 1, sourceName: String? = null): List<ExploreItem> = coroutineScope {
        val activeSources = if (sourceName == null) sources else sources.filter { it.name == sourceName }

        activeSources.map { source ->
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


