package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.repository.source.BrowseMode
import io.aatricks.easyreader.data.repository.source.NovelSource
import io.aatricks.easyreader.data.repository.source.isSourceEnabled
import io.aatricks.easyreader.util.normalizeExploreItemDetails
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

    private val enabledSources: List<NovelSource>
        get() = sources.filter(::isSourceEnabled)

    fun getAllSources(): List<NovelSource> = enabledSources

    suspend fun getPopularNovels(
        page: Int = 1,
        sourceName: String? = null,
        tags: List<String> = emptyList()
    ): List<ExploreItem> = getNovels(BrowseMode.POPULAR, page, sourceName, tags)

    suspend fun getNovels(
        mode: BrowseMode = BrowseMode.POPULAR,
        page: Int = 1,
        sourceName: String? = null,
        tags: List<String> = emptyList()
    ): List<ExploreItem> = coroutineScope {
        val activeSources = filterSources(sourceName)

        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val results = if (normalizedTags.size <= 1) {
            activeSources.map { source ->
                async { runCatching { source.getNovels(mode, page, normalizedTags) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        } else {
            activeSources.map { source ->
                async { loadNovelsWithTagIntersection(source, mode, page, normalizedTags) }
            }.awaitAll().flatten()
        }

        if (sourceName == null) results.shuffled() else results
    }
    
    suspend fun getTags(sourceName: String?): List<String> = coroutineScope {
        if (sourceName != null) {
            enabledSources.find { it.name == sourceName }?.getTags() ?: emptyList()
        } else {
            enabledSources.map { async { it.getTags() } }
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
    ): List<ExploreItem> = searchNovelsDetailed(query, page, sourceName).items

    /**
     * Search variant that returns per-source failure information alongside the
     * merged items. Use this when the UI should surface "MangaBat unavailable"
     * instead of letting a broken source disappear into an empty result list.
     */
    suspend fun searchNovelsDetailed(
        query: String,
        page: Int = 1,
        sourceName: String? = null
    ): SearchOutcome = coroutineScope {
        val activeSources = filterSources(sourceName)

        val outcomes = activeSources.map { source ->
            async {
                runCatching { source.searchNovels(query, page) }.fold(
                    onSuccess = { items -> source to Result.success(items) },
                    onFailure = { e -> source to Result.failure(e) }
                )
            }
        }.awaitAll()

        val items = outcomes.flatMap { (_, result) -> result.getOrDefault(emptyList()) }
        val failures = outcomes.mapNotNull { (source, result) ->
            result.exceptionOrNull()?.let { e ->
                SourceFailure(
                    sourceName = source.name,
                    reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName,
                    cause = e
                )
            }
        }
        SearchOutcome(items, failures)
    }

    suspend fun getNovelDetails(url: String, sourceName: String): ExploreItem? {
        val source = enabledSources.find { it.name == sourceName } ?: return null
        return runCatching { normalizeExploreItemDetails(source.getNovelDetails(url)) }.getOrNull()
    }

    private fun filterSources(sourceName: String?): List<NovelSource> {
        return if (sourceName == null) enabledSources else enabledSources.filter { it.name == sourceName }
    }

    private suspend fun loadNovelsWithTagIntersection(
        source: NovelSource,
        mode: BrowseMode,
        page: Int,
        tags: List<String>
    ): List<ExploreItem> = coroutineScope {
        val perTagResults = tags.map { tag ->
            async { runCatching { source.getNovels(mode, page, listOf(tag)) }.getOrDefault(emptyList()) }
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

    fun getSourceNames(): List<String> = enabledSources.map { it.name }
}
