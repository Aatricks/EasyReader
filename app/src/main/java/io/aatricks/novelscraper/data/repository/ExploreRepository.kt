package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.local.SourceManager
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.source.MangaBatSource
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.NovelSource
import io.aatricks.novelscraper.data.repository.source.SmartScraperSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ExploreRepository(context: Context? = null) {

    private val sourceManager = context?.let { SourceManager(it) }

    private val staticSources: List<NovelSource> = listOf(
        NovelFireSource(),
        MangaBatSource()
    )

    private val sources: List<NovelSource>
        get() = staticSources + (sourceManager?.getNovelSources() ?: emptyList())

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
        val source = sources.find { it.name == sourceName } 
            ?: if (sourceName.contains(".")) SmartScraperSource("https://$sourceName") else null
            ?: return null
            
        return try {
            source.getNovelDetails(url)
        } catch (e: Exception) {
            null
        }
    }

    fun getSourceNames(): List<String> = sources.map { it.name }
}


