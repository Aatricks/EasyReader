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
    @ApplicationContext private val context: android.content.Context,
    private val sources: Set<@JvmSuppressWildcards NovelSource>
) {

    fun getAllSources(): List<NovelSource> = sources.toList()

    suspend fun getPopularNovels(
        page: Int = 1,
        sourceName: String? = null,
        tags: List<String> = emptyList()
    ): List<ExploreItem> = coroutineScope {
        val activeSources = filterSources(sourceName)
        
        val results = activeSources.map { source ->
            async { runCatching { source.getPopularNovels(page, tags) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        if (sourceName == null) results.shuffled() else results
    }
    
    suspend fun getTags(sourceName: String?): List<String> = coroutineScope {
        if (sourceName != null) {
            sources.find { it.name == sourceName }?.getTags() ?: emptyList()
        } else {
            sources.map { async { it.getTags() } }
                .awaitAll()
                .flatten()
                .distinct()
                .sorted()
        }
    }
    
    suspend fun searchNovels(
        query: String,
        page: Int = 1,
        sourceName: String? = null
    ): List<ExploreItem> = coroutineScope {
        val activeSources = filterSources(sourceName)

        activeSources.map { source ->
            async { runCatching { source.searchNovels(query, page) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun getNovelDetails(url: String, sourceName: String): ExploreItem? {
        val source = sources.find { it.name == sourceName } ?: return null
        return runCatching { source.getNovelDetails(url) }.getOrNull()
    }

    private fun filterSources(sourceName: String?): List<NovelSource> {
        return if (sourceName == null) sources.toList() else sources.filter { it.name == sourceName }
    }

    fun getSourceNames(): List<String> = sources.map { it.name }
}


