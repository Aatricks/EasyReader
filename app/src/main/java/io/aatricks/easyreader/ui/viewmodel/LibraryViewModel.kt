package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.SortMode
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.work.ChapterDownloadQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class LibraryViewModel @Inject constructor(
    val repository: LibraryRepository,
    private val contentRepository: ContentRepository,
    private val exploreRepository: ExploreRepository,
    private val downloadQueue: ChapterDownloadQueue,
    private val downloadStatusReconciler: DownloadStatusReconciler
) : BaseViewModel<LibraryViewModel.LibraryUiState>(LibraryUiState()) {

    private val TAG = "LibraryViewModel"

    private val filters = LibraryFilters()
    val searchQuery: StateFlow<String> = filters.searchQuery
    val contentTypeFilter: StateFlow<ContentType?> = filters.contentTypeFilter
    val sortMode: StateFlow<SortMode> = filters.sortMode
    val statusFilter: StateFlow<SeriesReadingStatus> = filters.statusFilter

    fun setStatusFilter(filter: SeriesReadingStatus): Unit {
        filters.setStatusFilter(filter)
    }

    private val selectionManager = LibrarySelectionManager()
    private val _collapsedSources = MutableStateFlow<Set<String>>(emptySet())
    private val downloadStates = LibraryDownloadStates(
        scope = viewModelScope,
        repository = repository,
        contentRepository = contentRepository,
        downloadStatusReconciler = downloadStatusReconciler,
        downloadQueue = downloadQueue,
    )
    private val deletionCoordinator = LibraryDeletionCoordinator(
        scope = viewModelScope,
        repository = repository,
        contentRepository = contentRepository,
        onError = { message -> updateState { it.copy(error = message) } },
        onItemsRemoved = { urls ->
            urls.forEach { downloadQueue.cancel(it) }
            downloadStates.removeCacheStates(urls)
        }
    )
    val pendingDeletion: StateFlow<Set<String>> = deletionCoordinator.pendingDeletion

    init {
        _collapsedSources.value = repository.loadCollapsedSources()
        observeLibraryChanges()
    }

    fun reconcileDownloadedItemsOnDemand(): Unit {
        downloadStates.reconcileDownloadedItemsOnDemand()
    }

    data class LibraryUiState(
        val items: List<LibraryItem> = emptyList(),
        val filteredItems: List<LibraryItem> = emptyList(),
        val groupedItems: Map<String, List<LibraryItem>> = emptyMap(),
        val groupedBySource: Map<String, Map<String, List<LibraryItem>>> = emptyMap(),
        val collapsedSources: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val isSelectionMode: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val selectedCount: Int = 0,
        val isEmpty: Boolean = true,
        val currentlyReading: LibraryItem? = null,
        val chapterCacheStates: Map<String, PrefetchResult> = emptyMap()
    )

    private fun observeLibraryChanges(): Unit {
        viewModelScope.launch {
            val repoFlow = combine(
                repository.libraryItems,
                selectionManager.selectedItems,
                _collapsedSources,
                selectionManager.selectionModeEnabled
            ) { items, selected, collapsed, selectionModeEnabled ->
                Triple(items, selected, collapsed) to selectionModeEnabled
            }

            val cacheAndPending = combine(
                downloadStates.chapterCacheStates,
                deletionCoordinator.pendingDeletion,
                filters.statusFilter
            ) { c, p, s ->
                Triple(c, p, s)
            }

            combine(
                repoFlow,
                cacheAndPending,
                filters.searchQuery,
                filters.contentTypeFilter,
                filters.sortMode
            ) { repoState, cachePending, query, filter, sort ->
                val (repoData, selectionModeEnabled) = repoState
                val (rawItems, selectedIds, collapsedSources) = repoData
                val (cacheStates, pendingIds, statusFilter) = cachePending

                val items = if (pendingIds.isEmpty()) rawItems else rawItems.filterNot { it.id in pendingIds }
                val filteredItems = filters.apply(items, query, filter, sort, statusFilter)

                LibraryUiState(
                    items = items,
                    filteredItems = filteredItems,
                    groupedItems = repository.getGroupedByTitle(filteredItems),
                    groupedBySource = repository.getGroupedBySourceAndTitle(filteredItems),
                    collapsedSources = collapsedSources,
                    isSelectionMode = selectionModeEnabled || selectedIds.isNotEmpty(),
                    selectedIds = selectedIds,
                    selectedCount = selectedIds.size,
                    isEmpty = items.isEmpty(),
                    currentlyReading = items.find { it.isCurrentlyReading },
                    chapterCacheStates = cacheStates
                )
            }.collect { newState ->
                updateState { newState }
            }
        }
    }



    private fun scheduleDeletion(ids: Set<String>) {
        deletionCoordinator.schedule(ids)
    }

    fun undoPendingDeletion(): Unit {
        deletionCoordinator.undo()
    }

    fun flushPendingDeletion(): Unit {
        deletionCoordinator.flush()
    }

    fun removeItemsImmediate(ids: Set<String>): Unit {
        deletionCoordinator.removeImmediate(ids)
    }

    fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1"
    ): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true, error = null) }
                if (repository.getItemByUrl(url) != null) {
                    throw Exception("This item already exists in your library")
                }
                val baseTitle = TextUtils.extractBaseTitle(title, contentType)
                repository.addItem(
                    title = title.trim(),
                    url = url.trim(),
                    contentType = contentType,
                    currentChapter = currentChapter,
                    baseTitle = baseTitle
                )
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
    }

    fun addExploreItem(item: ExploreItem): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true) }
                addExploreItemInternal(item)
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Failed to add: ${e.message}") }
            }
        }
    }

    /**
     * Add a resolved [ExploreItem] (from Explore or from a pasted URL) as a proper series:
     * stores `baseTitle`/`baseNovelUrl`/`sourceName`/`totalChapters` so chapter pagination and
     * "open new chapter" work. Caller owns the coroutine + loading/error state.
     */
    private suspend fun addExploreItemInternal(item: ExploreItem) {
        val readingUrl = item.readingUrl
            ?: exploreRepository.getNovelDetails(item.url, item.source)?.readingUrl
            ?: item.url

        if (repository.getItemByUrl(readingUrl) != null) {
            throw Exception("Item already in library")
        }

        val contentType = determineContentType(readingUrl)
        if (contentType == ContentType.WEB) {
            addWebExploreItem(item, readingUrl)
        } else {
            repository.addItem(
                title = item.title,
                url = readingUrl,
                contentType = contentType,
                currentChapter = "Chapter 1",
                baseTitle = item.title,
                baseNovelUrl = item.url,
                sourceName = item.source,
                totalChapters = item.chapterCount
            )
        }
    }

    private fun determineContentType(url: String): ContentType {
        return when {
            url.endsWith(".epub", ignoreCase = true) -> ContentType.EPUB
            url.endsWith(".pdf", ignoreCase = true) -> ContentType.PDF
            else -> ContentType.WEB
        }
    }

    private suspend fun addWebExploreItem(
        item: ExploreItem,
        readingUrl: String
    ): Unit {
        val chapterTitle = contentRepository.fetchTitle(readingUrl) ?: "Chapter 1"
        val fullTitle = if (chapterTitle.contains(item.title, ignoreCase = true)) {
            chapterTitle
        } else {
            "${item.title} - $chapterTitle"
        }
        repository.addItem(
            title = fullTitle,
            url = readingUrl,
            contentType = ContentType.WEB,
            currentChapter = TextUtils.extractChapterLabel(chapterTitle) ?: "Chapter 1",
            baseTitle = item.title,
            baseNovelUrl = item.url,
            sourceName = item.source,
            totalChapters = item.chapterCount
        )
    }

    fun addChapters(
        chapters: List<io.aatricks.easyreader.data.model.ChapterInfo>,
        baseTitle: String,
        baseNovelUrl: String,
        sourceName: String
    ): Unit {
        viewModelScope.launch {
            var failedToQueueAny = false
            chapters.forEach { chapter ->
                runCatching {
                    repository.getItemByUrl(chapter.url)
                        ?: repository.addItem(
                            title = chapter.title,
                            url = chapter.url,
                            contentType = ContentType.WEB,
                            currentChapter = TextUtils.extractChapterLabel(chapter.title)
                                ?: TextUtils.extractChapterLabelFromUrl(chapter.url)
                                ?: chapter.title,
                            baseTitle = baseTitle,
                            baseNovelUrl = baseNovelUrl,
                            sourceName = sourceName
                        )
                }.onSuccess {
                    val success = downloadStates.markPendingAndEnqueue(chapter.url)
                    if (!success) {
                        failedToQueueAny = true
                    }
                }.onFailure { e ->
                    updateState { state ->
                        state.copy(error = "Failed to queue chapter download: ${e.message}")
                    }
                }
            }
            if (failedToQueueAny) {
                updateState { it.copy(error = "Failed to queue download") }
            }
        }
    }

    // All DB-flag writes go through [downloadStatusReconciler] so the badge, the DB flag,
    // and on-disk state cannot disagree. See DownloadStatusReconciler for the rule.

    fun fetchAndAdd(url: String): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true, error = null) }
                val trimmed = url.trim()
                if (repository.getItemByUrl(trimmed) != null) {
                    throw Exception("This item already exists in your library")
                }
                val contentType = contentRepository.inferContentType(trimmed)

                when {
                    contentType == ContentType.EPUB -> {
                        val fetchedTitle = runCatching { contentRepository.fetchTitle(trimmed) }.getOrNull() ?: trimmed
                        repository.addItem(
                            title = fetchedTitle.trim().ifBlank { trimmed },
                            url = trimmed,
                            contentType = ContentType.EPUB,
                            currentChapter = "Chapter 1",
                            baseTitle = fetchedTitle.trim().ifBlank { trimmed },
                            baseNovelUrl = trimmed,
                            sourceName = "EPUB"
                        )
                    }
                    // A web series URL: resolve it to a source (Novelight/NovelFire/… by host, or
                    // SmartSource) and add it as a proper, paginating series with its chapter list.
                    contentType == ContentType.WEB && trimmed.startsWith("http") &&
                        addResolvedSeries(trimmed) -> Unit
                    // Fallback: not resolvable as a series (arbitrary page or local file) — keep the
                    // legacy single-item behaviour so pasting a lone chapter URL still works.
                    else -> addUnresolvedItem(trimmed, contentType)
                }
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
    }

    /** Returns true if [url] resolved to a source series (with chapters) and was added. */
    private suspend fun addResolvedSeries(url: String): Boolean {
        val item = runCatching { exploreRepository.getNovelDetailsByUrl(url) }.getOrNull()
        if (item == null || item.chapters.isEmpty()) return false
        addExploreItemInternal(item)
        return true
    }

    private suspend fun addUnresolvedItem(url: String, contentType: ContentType) {
        val fetchedTitle = runCatching { contentRepository.fetchTitle(url) }.getOrNull() ?: url
        val fullTitle = fetchedTitle.trim().ifBlank { url }
        val baseTitle = TextUtils.extractBaseTitle(fullTitle, contentType)
        repository.addItem(
            title = fullTitle,
            url = url,
            contentType = contentType,
            currentChapter = TextUtils.extractChapterLabel(fullTitle) ?: "Chapter 1",
            baseTitle = baseTitle,
            baseNovelUrl = url,
            sourceName = if (url.startsWith("http")) "Web" else "File"
        )
    }

    fun openNewChapter(
        baseTitle: String,
        baseNovelUrl: String,
        sourceName: String,
        onChapterLoaded: (String, String) -> Unit
    ): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true) }
                val details = exploreRepository.getNovelDetails(baseNovelUrl, sourceName)
                val normalizedChapters = normalizeChapterList(details?.chapters.orEmpty())
                if (details == null || normalizedChapters.isEmpty()) {
                    throw Exception("No chapters found for this novel")
                }

                val latestChapter = selectLatestChapter(normalizedChapters)
                    ?: throw Exception("No latest chapter found for this novel")
                var item = repository.getItemByUrl(latestChapter.url)
                
                if (item == null) {
                    item = repository.addItem(
                        title = latestChapter.title,
                        url = latestChapter.url,
                        contentType = ContentType.WEB,
                        currentChapter = TextUtils.extractChapterLabel(latestChapter.title) 
                            ?: TextUtils.extractChapterLabelFromUrl(latestChapter.url) 
                            ?: latestChapter.title,
                        baseTitle = baseTitle,
                        baseNovelUrl = baseNovelUrl,
                        sourceName = sourceName,
                        totalChapters = normalizedChapters.size
                    )
                } else if (item.totalChapters < normalizedChapters.size) {
                    repository.updateItem(item.copy(totalChapters = normalizedChapters.size))
                }
                
                repository.clearUpdateIndicator(item.id)
                onChapterLoaded(item.url, item.id)
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                Log.e(TAG, "Failed to open new chapter", e)
                updateState { it.copy(isLoading = false, error = "Failed to load new chapter: ${e.message}") }
            }
        }
    }

    fun prefetchLibrary(selectedOnly: Boolean = false): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true) }
                val items = if (selectedOnly) {
                    val selectedIds = selectionManager.selectedIds
                    repository.libraryItems.value.filter { it.id in selectedIds }
                } else {
                    repository.libraryItems.value
                }
                items.forEach { item ->
                    downloadStates.markPendingAndEnqueue(item.url)
                }
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Prefetch failed: ${e.message}") }
            }
        }
    }

    fun retryDownload(url: String): Unit {
        viewModelScope.launch {
            runCatching { contentRepository.clearPermanentFailures(url) }
                .onFailure { e -> Log.w(TAG, "failed to clear permanent failures before retry: ${e.message}") }
            val success = downloadStates.markPendingAndEnqueue(url, replaceExisting = true)
            if (!success) {
                updateState { it.copy(error = "Failed to queue download") }
            }
        }
    }

    fun removeItem(itemId: String): Unit {
        scheduleDeletion(setOf(itemId))
    }

    fun removeDownload(itemId: String): Unit {
        viewModelScope.launch {
            val item = repository.getItemById(itemId) ?: return@launch
            downloadQueue.cancel(item.url)
            runCatching {
                contentRepository.clearDownload(item.url)
                repository.markDownloaded(itemId, false)
            }.onSuccess {
                downloadStates.refreshChapterCacheStates(listOf(item.url))
            }
        }
    }

    fun removeItems(itemIds: Set<String>): Unit {
        scheduleDeletion(itemIds)
    }

    fun removeGroup(baseTitle: String): Unit {
        val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
        if (groupItems.isNotEmpty()) {
            scheduleDeletion(groupItems.map { it.id }.toSet())
        }
    }

    fun updateItem(item: LibraryItem): Unit {
        viewModelScope.launch {
            runCatching { repository.updateItem(item) }
                .onFailure { e -> updateState { it.copy(error = "Failed to update item: ${e.message}") } }
        }
    }

    fun updateProgress(itemId: String, currentChapter: String, progress: Int): Unit {
        viewModelScope.launch {
            runCatching { repository.updateProgress(itemId, currentChapter, progress) }
        }
    }

    fun markAsCurrentlyReading(itemId: String): Unit {
        viewModelScope.launch {
            runCatching { repository.markAsCurrentlyReading(itemId) }
                .onFailure { e -> updateState { it.copy(error = "Failed to mark item: ${e.message}") } }
        }
    }

    fun toggleSelection(itemId: String): Unit {
        selectionManager.toggle(itemId)
    }

    fun selectItem(itemId: String): Unit {
        selectionManager.select(itemId)
    }

    fun deselectItem(itemId: String): Unit {
        selectionManager.deselect(itemId)
    }

    fun toggleGroupSelection(baseTitle: String): Unit {
        viewModelScope.launch {
            val itemIds = uiState.value.groupedItems[baseTitle]?.map { it.id } ?: emptyList()
            selectionManager.toggleGroup(itemIds)
        }
    }

    fun selectAll(): Unit {
        selectionManager.selectAll(repository.libraryItems.value.map { it.id }.toSet())
    }

    fun enterSelectionMode(): Unit {
        selectionManager.enterSelectionMode()
    }

    fun clearSelection(): Unit {
        selectionManager.clear()
    }

    fun updateSearchQuery(query: String): Unit {
        filters.setSearchQuery(query)
    }

    fun setContentTypeFilter(contentType: ContentType?): Unit {
        filters.setContentTypeFilter(contentType)
    }

    fun setSortMode(mode: SortMode): Unit {
        filters.setSortMode(mode)
    }

    fun refreshChapterCacheStates(urls: Collection<String>): Unit {
        downloadStates.refreshChapterCacheStates(urls)
    }

    fun removeSelectedItems(): Unit {
        val selectedIds = selectionManager.selectedIds
        if (selectedIds.isEmpty()) return
        scheduleDeletion(selectedIds)
        selectionManager.clear()
    }

    fun clearLibrary(): Unit {
        viewModelScope.launch {
            runCatching {
                repository.clearLibrary()
                contentRepository.clearAllCache()
                selectionManager.clear()
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to clear library: ${e.message}") }
            }
        }
    }

    fun toggleSourceExpansion(sourceName: String): Unit {
        val current = _collapsedSources.value.toMutableSet()
        if (!current.add(sourceName)) {
            current.remove(sourceName)
        }
        _collapsedSources.value = current
        repository.saveCollapsedSources(current)
    }

    fun resetProgress(itemId: String): Unit {
        viewModelScope.launch {
            runCatching {
                repository.getItemById(itemId)?.let { item ->
                    contentRepository.clearCachesForUrls(listOf(item.url))
                }
                repository.resetProgress(itemId)
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to reset progress: ${e.message}") }
            }
        }
    }

    fun resetNovelProgress(baseTitle: String): Unit {
        viewModelScope.launch {
            runCatching {
                val chapters = repository.getChaptersByBaseTitle(baseTitle)
                contentRepository.clearCachesForUrls(chapters.map { it.url })
                repository.resetProgressByBaseTitle(baseTitle)
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to reset novel progress: ${e.message}") }
            }
        }
    }

}

internal fun selectLatestChapter(chapters: List<ChapterInfo>): ChapterInfo? {
    if (chapters.isEmpty()) return null

    val latestByNumber = chapters.withIndex()
        .mapNotNull { indexedChapter ->
            val chapter = indexedChapter.value
            val chapterNumber = TextUtils.extractChapterNumber(chapter.title)
                ?: TextUtils.extractChapterNumber(chapter.url)
                ?: return@mapNotNull null
            Triple(chapterNumber, indexedChapter.index, chapter)
        }
        .maxWithOrNull(compareBy<Triple<Double, Int, ChapterInfo>>({ it.first }, { it.second }))
        ?.third

    return latestByNumber ?: chapters.lastOrNull()
}
