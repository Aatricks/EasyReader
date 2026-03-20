package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.ExploreItem
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

        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val results = if (normalizedTags.size <= 1) {
            activeSources.map { source ->
                async { runCatching { source.getPopularNovels(page, normalizedTags) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        } else {
            activeSources.map { source ->
                async { loadPopularNovelsWithTagIntersection(source, page, normalizedTags) }
            }.awaitAll().flatten()
        }

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

    private suspend fun loadPopularNovelsWithTagIntersection(
        source: NovelSource,
        page: Int,
        tags: List<String>
    ): List<ExploreItem> = coroutineScope {
        val perTagResults = tags.map { tag ->
            async { runCatching { source.getPopularNovels(page, listOf(tag)) }.getOrDefault(emptyList()) }
        }.awaitAll()

        intersectByUrl(perTagResults)
    }

    private fun intersectByUrl(resultSets: List<List<ExploreItem>>): List<ExploreItem> {
        if (resultSets.isEmpty() || resultSets.any { it.isEmpty() }) return emptyList()

        val commonUrls = resultSets
            .map { it.map(ExploreItem::url).toSet() }
            .reduce { acc, urls -> acc.intersect(urls) }

        return resultSets.first().filter { it.url in commonUrls }
    }

    fun getSourceNames(): List<String> = sources.map { it.name }
}
