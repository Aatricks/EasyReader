package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.repository.source.BrowseMode
import io.aatricks.easyreader.data.repository.source.NovelSource
import io.aatricks.easyreader.data.repository.source.SmartSource
import io.aatricks.easyreader.data.repository.source.isSourceEnabled
import io.aatricks.easyreader.util.inferBaseNovelUrlFromUrl
import io.aatricks.easyreader.util.inferSourceNameFromUrl
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
    ): List<ExploreItem> = getNovelsDetailed(mode, page, sourceName, tags).items

    /**
     * Browse variant that returns per-source failure information alongside the
     * merged items, mirroring [searchNovelsDetailed]. Sources fail independently;
     * the multi-tag intersection path swallows failures internally and reports none.
     */
    suspend fun getNovelsDetailed(
        mode: BrowseMode = BrowseMode.POPULAR,
        page: Int = 1,
        sourceName: String? = null,
        tags: List<String> = emptyList()
    ): SearchOutcome = coroutineScope {
        val activeSources = filterSources(sourceName)

        val normalizedTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedTags.size <= 1) {
            val outcomes = activeSources.map { source ->
                async {
                    runCatching { source.getNovels(mode, page, normalizedTags) }.fold(
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
            SearchOutcome(if (sourceName == null) items.shuffled() else items, failures)
        } else {
            val results = activeSources.map { source ->
                async { loadNovelsWithTagIntersection(source, mode, page, normalizedTags) }
            }.awaitAll().flatten()
            SearchOutcome(if (sourceName == null) results.shuffled() else results, emptyList())
        }
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

        val items = outcomes
            .flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            .let { filterByQueryRelevance(it, query) }
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

    // Sources sometimes fall back to their popular/browse page when search yields no hits
    // (failed JSON endpoint, ignored `q=` param, etc). Drop anything whose title doesn't
    // share every query token so the UI doesn't surface unrelated bestsellers.
    private fun filterByQueryRelevance(items: List<ExploreItem>, query: String): List<ExploreItem> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val title = item.title.lowercase()
            tokens.all { it in title }
        }
    }

    suspend fun getNovelDetails(url: String, sourceName: String): ExploreItem? {
        val source = enabledSources.find { it.name == sourceName } ?: return null
        return runCatching { normalizeExploreItemDetails(source.getNovelDetails(url)) }.getOrNull()
    }

    /**
     * Resolve a pasted series/book URL to a source and fetch its details. Picks the source whose
     * [NovelSource.baseUrl] host matches the URL's host (e.g. a novelight.net link → Novelight),
     * falling back to [SmartSource] (its `baseUrl` is blank, the universal heuristic scraper).
     * Lets "add by URL" build a proper, paginating series instead of a single orphan page.
     */
    suspend fun getNovelDetailsByUrl(url: String): ExploreItem? {
        val host = hostOf(url) ?: return null
        val match = enabledSources.firstOrNull { source ->
            source.baseUrl.isNotBlank() && hostOf(source.baseUrl) == host
        } ?: enabledSources.firstOrNull { it is SmartSource }
        return match?.let { getNovelDetails(url, it.name) }
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

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

    fun inferSourceName(url: String): String = inferSourceNameFromUrl(url)

    fun inferBaseNovelUrl(url: String): String = inferBaseNovelUrlFromUrl(url)

    fun getSourceNames(): List<String> = enabledSources.map { it.name }
}
