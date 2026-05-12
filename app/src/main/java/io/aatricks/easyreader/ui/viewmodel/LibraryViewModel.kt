package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.SortMode
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.seriesReadingStatus
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.normalizeChapterList
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class LibraryViewModel @Inject constructor(
    val repository: LibraryRepository,
    private val contentRepository: ContentRepository,
    private val exploreRepository: ExploreRepository
) : BaseViewModel<LibraryViewModel.LibraryUiState>(LibraryUiState()) {

    private val TAG = "LibraryViewModel"

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _contentTypeFilter = MutableStateFlow<ContentType?>(null)
    val contentTypeFilter: StateFlow<ContentType?> = _contentTypeFilter.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.LAST_READ)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _statusFilter = MutableStateFlow(SeriesReadingStatus.ALL)
    val statusFilter: StateFlow<SeriesReadingStatus> = _statusFilter.asStateFlow()

    fun setStatusFilter(filter: SeriesReadingStatus): Unit {
        _statusFilter.value = filter
    }

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _selectionModeEnabled = MutableStateFlow(false)
    private val _collapsedSources = MutableStateFlow<Set<String>>(emptySet())
    private val _chapterCacheStates = MutableStateFlow<Map<String, PrefetchResult>>(emptyMap())
    private val _pendingDeletion = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeletion: StateFlow<Set<String>> = _pendingDeletion.asStateFlow()
    private var pendingDeleteJob: Job? = null
    private var pendingDeleteUrls: List<String> = emptyList()

    companion object {
        private const val UNDO_DELETE_WINDOW_MS = 5000L
        private const val LIBRARY_PREFETCH_CONCURRENCY = 4
    }

    init {
        _collapsedSources.value = repository.loadCollapsedSources()
        observeLibraryChanges()
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
                _selectedItems,
                _collapsedSources,
                _selectionModeEnabled
            ) { items, selected, collapsed, selectionModeEnabled ->
                Triple(items, selected, collapsed) to selectionModeEnabled
            }

            val cacheAndPending = combine(_chapterCacheStates, _pendingDeletion, _statusFilter) { c, p, s ->
                Triple(c, p, s)
            }

            combine(
                repoFlow,
                cacheAndPending,
                _searchQuery,
                _contentTypeFilter,
                _sortMode
            ) { repoState, cachePending, query, filter, sort ->
                val (repoData, selectionModeEnabled) = repoState
                val (rawItems, selectedIds, collapsedSources) = repoData
                val (cacheStates, pendingIds, statusFilter) = cachePending

                val items = if (pendingIds.isEmpty()) rawItems else rawItems.filterNot { it.id in pendingIds }
                val filteredItems = filterAndSortItems(items, query, filter, sort, statusFilter)

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
        if (ids.isEmpty()) return
        val items = repository.libraryItems.value
        val urls = ids.mapNotNull { id -> items.firstOrNull { it.id == id }?.url }
        pendingDeleteJob?.cancel()
        pendingDeleteUrls = (pendingDeleteUrls + urls).distinct()
        _pendingDeletion.update { it + ids }
        pendingDeleteJob = viewModelScope.launch {
            delay(UNDO_DELETE_WINDOW_MS)
            commitPendingDeletion()
        }
    }

    fun undoPendingDeletion(): Unit {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        _pendingDeletion.value = emptySet()
        pendingDeleteUrls = emptyList()
    }

    private suspend fun commitPendingDeletion() {
        val ids = _pendingDeletion.value
        if (ids.isEmpty()) return
        val urls = pendingDeleteUrls
        runCatching {
            contentRepository.clearCachesForUrls(urls)
            repository.removeItems(ids)
        }.onFailure { e ->
            updateState { it.copy(error = "Failed to remove items: ${e.message}") }
        }
        _pendingDeletion.value = emptySet()
        pendingDeleteUrls = emptyList()
        pendingDeleteJob = null
    }

    fun flushPendingDeletion(): Unit {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        viewModelScope.launch { commitPendingDeletion() }
    }

    private fun filterAndSortItems(
        items: List<LibraryItem>,
        query: String,
        filter: ContentType?,
        sort: SortMode,
        statusFilter: SeriesReadingStatus = SeriesReadingStatus.ALL
    ): List<LibraryItem> {
        var filtered = items

        if (filter != null) {
            filtered = filtered.filter { it.contentType == filter }
        }

        if (statusFilter != SeriesReadingStatus.ALL) {
            val seriesGroups = filtered.groupBy { it.libraryNovelKey() }
            val matchingKeys = seriesGroups
                .filterValues { groupItems -> seriesReadingStatus(groupItems) == statusFilter }
                .keys
            filtered = filtered.filter { it.libraryNovelKey() in matchingKeys }
        }

        if (query.isNotBlank()) {
            val lowercaseQuery = query.trim().lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(lowercaseQuery) ||
                it.baseTitle.lowercase().contains(lowercaseQuery)
            }
        }

        return when (sort) {
            SortMode.LAST_READ -> filtered.sortedByDescending { it.lastRead }
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.PROGRESS -> filtered.sortedByDescending { it.progress }
        }
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

    fun addExploreItem(
        item: ExploreItem,
        exploreRepository: ExploreRepository
    ): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true) }
                val readingUrl = item.readingUrl ?: exploreRepository.getNovelDetails(item.url, item.source)?.readingUrl ?: item.url
                
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
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Failed to add: ${e.message}") }
            }
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
            chapters.forEach { chapter ->
                runCatching {
                    if (repository.getItemByUrl(chapter.url) == null) {
                        repository.addItem(
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
                        setCacheState(
                            PrefetchResult(
                                url = chapter.url,
                                htmlCached = false,
                                totalImages = 0,
                                cachedImages = 0,
                                isComplete = false,
                                isInProgress = true
                            )
                        )
                        setCacheState(contentRepository.prefetch(chapter.url, PrefetchMode.USER_REQUESTED))
                    }
                }
            }
        }
    }

    fun fetchAndAdd(url: String): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true, error = null) }
                if (repository.getItemByUrl(url) != null) {
                    throw Exception("This item already exists in your library")
                }
                val contentType = contentRepository.inferContentType(url)
                val fetchedTitle = runCatching { contentRepository.fetchTitle(url) }.getOrNull() ?: url
                
                if (contentType == ContentType.EPUB) {
                    repository.addItem(
                        title = fetchedTitle.trim().ifBlank { url },
                        url = url.trim(),
                        contentType = ContentType.EPUB,
                        currentChapter = "Chapter 1",
                        baseTitle = fetchedTitle.trim().ifBlank { url },
                        baseNovelUrl = url,
                        sourceName = "EPUB"
                    )
                } else {
                    val fullTitle = fetchedTitle.trim().ifBlank { url }
                    val baseTitle = TextUtils.extractBaseTitle(fullTitle, contentType)
                    repository.addItem(
                        title = fullTitle,
                        url = url.trim(),
                        contentType = contentType,
                        currentChapter = TextUtils.extractChapterLabel(fullTitle) ?: "Chapter 1",
                        baseTitle = baseTitle,
                        baseNovelUrl = url,
                        sourceName = if (url.startsWith("http")) "Web" else "File"
                    )
                }
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
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
                    val selectedIds = _selectedItems.value
                    repository.libraryItems.value.filter { it.id in selectedIds }
                } else {
                    repository.libraryItems.value
                }
                val gate = Semaphore(LIBRARY_PREFETCH_CONCURRENCY)
                supervisorScope {
                    items.map { item ->
                        async {
                            setCacheState(
                                PrefetchResult(
                                    url = item.url,
                                    htmlCached = false,
                                    totalImages = 0,
                                    cachedImages = 0,
                                    isComplete = false,
                                    isInProgress = true
                                )
                            )
                            gate.withPermit {
                                runCatching {
                                    setCacheState(contentRepository.prefetchWithProgress(item.url, PrefetchMode.USER_REQUESTED) { setCacheState(it) })
                                }
                            }
                        }
                    }.awaitAll()
                }
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Prefetch failed: ${e.message}") }
            }
        }
    }

    fun retryDownload(url: String): Unit {
        viewModelScope.launch {
            setCacheState(
                (uiState.value.chapterCacheStates[url] ?: PrefetchResult(url, false, 0, 0, false))
                    .copy(isInProgress = true, isRetryable = false)
            )
            runCatching {
                setCacheState(contentRepository.prefetchWithProgress(url, PrefetchMode.USER_REQUESTED) { setCacheState(it) })
            }
        }
    }

    fun removeItem(itemId: String): Unit {
        scheduleDeletion(setOf(itemId))
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
        _selectedItems.update {
            val current = it.toMutableSet()
            if (!current.add(itemId)) current.remove(itemId)
            current
        }
    }

    fun selectItem(itemId: String): Unit {
        _selectedItems.update { it + itemId }
    }

    fun deselectItem(itemId: String): Unit {
        _selectedItems.update { it - itemId }
    }

    fun toggleGroupSelection(baseTitle: String): Unit {
        viewModelScope.launch {
            val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
            val selectedIds = uiState.value.selectedIds
            val allSelected = groupItems.all { it.id in selectedIds }
            val itemIds = groupItems.map { it.id }
            
            if (allSelected) {
                _selectedItems.update { it - itemIds.toSet() }
            } else {
                _selectedItems.update { it + itemIds.toSet() }
            }
        }
    }

    fun selectAll(): Unit {
        _selectionModeEnabled.value = true
        _selectedItems.value = repository.libraryItems.value.map { it.id }.toSet()
    }

    fun enterSelectionMode(): Unit {
        _selectionModeEnabled.value = true
    }

    fun clearSelection(): Unit {
        _selectedItems.value = emptySet()
        _selectionModeEnabled.value = false
    }

    fun updateSearchQuery(query: String): Unit {
        _searchQuery.value = query
    }

    fun setContentTypeFilter(contentType: ContentType?): Unit {
        _contentTypeFilter.value = contentType
    }

    fun setSortMode(mode: SortMode): Unit {
        _sortMode.value = mode
    }

    fun refreshChapterCacheStates(urls: Collection<String>): Unit {
        val targetUrls = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (targetUrls.isEmpty()) return

        viewModelScope.launch {
            val results = supervisorScope {
                targetUrls.map { url ->
                    async { runCatching { contentRepository.inspectCache(url) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            if (results.isNotEmpty()) {
                _chapterCacheStates.update { current ->
                    current + results.associateBy { it.url }
                }
            }
        }
    }

    fun removeSelectedItems(): Unit {
        val selectedIds = _selectedItems.value
        if (selectedIds.isEmpty()) return
        scheduleDeletion(selectedIds)
        _selectedItems.value = emptySet()
        _selectionModeEnabled.value = false
    }

    fun clearLibrary(): Unit {
        viewModelScope.launch {
            runCatching {
                repository.clearLibrary()
                contentRepository.clearAllCache()
                _selectedItems.value = emptySet()
                _selectionModeEnabled.value = false
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

    private fun setCacheState(result: PrefetchResult): Unit {
        _chapterCacheStates.update { current ->
            current + (result.url to result)
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
