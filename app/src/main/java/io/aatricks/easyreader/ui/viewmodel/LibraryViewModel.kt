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
import kotlinx.coroutines.Job
import io.aatricks.easyreader.util.rethrowCancellation
import javax.inject.Inject
import android.util.Log
import io.aatricks.easyreader.ui.screens.DrawerNovelSections
import io.aatricks.easyreader.ui.screens.buildDrawerNovelSections
import kotlinx.coroutines.Dispatchers
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.data.model.libraryNovelKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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
        backfillMissingCovers()
    }

    private fun backfillMissingCovers() {
        if (coversBackfillAttempted.compareAndSet(false, true)) {
            viewModelScope.launch(defaultDispatcher) {
                runCatching {
                    val items = repository.getAllItemsSnapshot()
                    val itemsToBackfill = items.filter {
                        it.coverImageUrl.isBlank() && it.contentType == ContentType.WEB
                    }
                    if (itemsToBackfill.isEmpty()) return@launch

                    val grouped = itemsToBackfill.groupBy { it.libraryNovelKey() }
                    val semaphore = Semaphore(BACKFILL_CONCURRENCY)
                    val jobs = grouped.values.map { novelGroup ->
                        async {
                            semaphore.withPermit {
                                val firstItem = novelGroup.first()
                                val url = firstItem.baseNovelUrl.ifBlank { firstItem.url }
                                val sourceName = firstItem.sourceName
                                val displayTitle = firstItem.libraryDisplayTitle()
                                
                                runCatching {
                                    val knownSources = exploreRepository.getSourceNames()
                                    val details = if (knownSources.contains(sourceName)) {
                                        exploreRepository.getNovelDetails(url, sourceName)
                                    } else {
                                        exploreRepository.getNovelDetailsByUrl(url)
                                    }
                                    
                                    val coverUrl = details?.coverUrl
                                    if (!coverUrl.isNullOrBlank()) {
                                        repository.updateCoverImageUrl(displayTitle, sourceName, coverUrl)
                                    }
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            }
        }
    }

    fun reconcileDownloadedItemsOnDemand(): Unit {
        downloadStates.reconcileDownloadedItemsOnDemand()
    }

    override val uiState: StateFlow<LibraryUiState> = combine(
        combine(
            repository.libraryItems,
            selectionManager.selectedItems,
            _collapsedSources,
            selectionManager.selectionModeEnabled
        ) { items, selected, collapsed, selectionModeEnabled ->
            Triple(items, selected, collapsed) to selectionModeEnabled
        },
        combine(
            downloadStates.chapterCacheStates,
            deletionCoordinator.pendingDeletion,
            filters.statusFilter
        ) { c, p, s ->
            Triple(c, p, s)
        },
        combine(
            filters.searchQuery,
            filters.contentTypeFilter,
            filters.sortMode
        ) { query, filter, sort ->
            Triple(query, filter, sort)
        },
        _uiState
    ) { repoState, cachePending, filterParams, manualUiState ->
        val (repoData, selectionModeEnabled) = repoState
        val (rawItems, selectedIds, collapsedSources) = repoData
        val (cacheStates, pendingIds, statusFilter) = cachePending
        val (query, filter, sort) = filterParams

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
            chapterCacheStates = cacheStates,
            isLoading = manualUiState.isLoading,
            error = manualUiState.error,
            snackbarMessage = manualUiState.snackbarMessage
        )
    }
    .flowOn(defaultDispatcher)
    .stateIn(
        scope = viewModelScope,
        // Stop the moment no screen observes (library screen / chapter-list sheet). The heavy
        // getGroupedByTitle/BySource run only while one is on-screen; the last value is retained
        // (default replayExpiration) so re-entry is warm with no empty flash.
        started = if (isUnderTest) SharingStarted.Eagerly else SharingStarted.WhileSubscribed(0),
        initialValue = LibraryUiState()
    )

    /**
     * Lean state for the reader's library drawer. The drawer needs ONLY the quick-access sections
     * and an empty flag, so it deliberately does NOT go through [uiState]'s whole-library
     * getGroupedByTitle/getGroupedBySourceAndTitle aggregation: opening the drawer over a large
     * library would otherwise burst-allocate those maps on Default, and the resulting GC could
     * evict decoded reader bitmaps (re-decode stutter on resume). Recomputed off-main, stopped the
     * instant the drawer closes.
     */
    val drawerUiState: StateFlow<DrawerUiState> = combine(
        repository.libraryItems,
        deletionCoordinator.pendingDeletion
    ) { rawItems, pendingIds ->
        val items = if (pendingIds.isEmpty()) rawItems else rawItems.filterNot { it.id in pendingIds }
        DrawerUiState(buildDrawerNovelSections(items), items.isEmpty())
    }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = if (isUnderTest) SharingStarted.Eagerly else SharingStarted.WhileSubscribed(0),
            initialValue = DrawerUiState(DrawerNovelSections(null, emptyList(), emptyList()), true)
        )

    data class LibraryUiState(
        val items: List<LibraryItem> = emptyList(),
        val filteredItems: List<LibraryItem> = emptyList(),
        val groupedItems: Map<String, List<LibraryItem>> = emptyMap(),
        val groupedBySource: Map<String, Map<String, List<LibraryItem>>> = emptyMap(),
        val collapsedSources: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val snackbarMessage: String? = null,
        val isSelectionMode: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val selectedCount: Int = 0,
        val isEmpty: Boolean = true,
        val currentlyReading: LibraryItem? = null,
        val chapterCacheStates: Map<String, PrefetchResult> = emptyMap()
    )

    data class DrawerUiState(
        val sections: DrawerNovelSections,
        val isLibraryEmpty: Boolean
    )



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
        val details = if (item.readingUrl == null) {
            exploreRepository.getNovelDetails(item.url, item.source)
        } else null
        val readingUrl = item.readingUrl
            ?: details?.readingUrl
            ?: item.url

        if (repository.getItemByUrl(readingUrl) != null) {
            throw Exception("Item already in library")
        }

        val contentType = determineContentType(readingUrl)
        val coverImageUrl = item.coverUrl ?: details?.coverUrl ?: ""
        if (contentType == ContentType.WEB) {
            addWebExploreItem(item.copy(coverUrl = coverImageUrl), readingUrl)
        } else {
            repository.addItem(
                title = item.title,
                url = readingUrl,
                contentType = contentType,
                currentChapter = "Chapter 1",
                baseTitle = item.title,
                baseNovelUrl = item.url,
                sourceName = item.source,
                totalChapters = item.chapterCount,
                coverImageUrl = coverImageUrl
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
            totalChapters = item.chapterCount,
            coverImageUrl = item.coverUrl.orEmpty()
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
                updateState {
                    it.copy(
                        isLoading = true,
                        error = null,
                        snackbarMessage = null
                    )
                }
                val trimmed = url.trim()
                if (repository.getItemByUrl(trimmed) != null) {
                    throw Exception("This item already exists in your library")
                }
                val contentType = contentRepository.inferContentType(trimmed)

                val addedTitle = when {
                    contentType == ContentType.EPUB -> {
                        val fetched = runCatching { contentRepository.fetchTitle(trimmed) }
                        val fetchedTitle = fetched.getOrNull() ?: trimmed
                        val finalTitle = fetchedTitle.trim().ifBlank { trimmed }
                        repository.addItem(
                            title = finalTitle,
                            url = trimmed,
                            contentType = ContentType.EPUB,
                            currentChapter = "Chapter 1",
                            baseTitle = finalTitle,
                            baseNovelUrl = trimmed,
                            sourceName = "EPUB"
                        )
                        finalTitle
                    }
                    contentType == ContentType.WEB && trimmed.startsWith("http") -> {
                        val resolvedTitle = addResolvedSeries(trimmed)
                        resolvedTitle ?: addUnresolvedItem(trimmed, contentType)
                    }
                    else -> addUnresolvedItem(trimmed, contentType)
                }
                updateState {
                    it.copy(
                        isLoading = false,
                        snackbarMessage = "Added \"$addedTitle\" to library",
                        error = null
                    )
                }
            }.onFailure { e ->
                updateState {
                    it.copy(
                        isLoading = false,
                        error = "Failed to add item: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Returns the series title if [url] resolved to a source series
     * (with chapters) and was added, null otherwise.
     */
    private suspend fun addResolvedSeries(url: String): String? {
        val item = runCatching { exploreRepository.getNovelDetailsByUrl(url) }.getOrNull()
        if (item == null || item.chapters.isEmpty()) return null
        addExploreItemInternal(item)
        return item.title
    }

    fun consumeSnackbarMessage() {
        updateState { it.copy(snackbarMessage = null) }
    }

    fun consumeError() {
        updateState { it.copy(error = null) }
    }

    private var openNewChapterJob: Job? = null

    private suspend fun addUnresolvedItem(url: String, contentType: ContentType): String {
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
        return baseTitle.ifBlank { fullTitle }
    }

    fun openNewChapter(
        baseTitle: String,
        baseNovelUrl: String,
        sourceName: String,
        onChapterLoaded: (String, String) -> Unit
    ): Unit {
        if (openNewChapterJob?.isActive == true) return
        openNewChapterJob = viewModelScope.launch {
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
            }.rethrowCancellation().onFailure { e ->
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
                downloadQueue.cancelAll()
                repository.clearLibrary()
                contentRepository.clearAllCache()
                contentRepository.clearAllDownloads()
                contentRepository.clearImportedEpubs()
                selectionManager.clear()
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to clear library: ${e.message}") }
            }
        }
    }

    fun clearAllDownloads(): Unit {
        viewModelScope.launch {
            val downloaded = repository.getDownloadedItems()
            downloadQueue.cancelAll()
            runCatching {
                contentRepository.clearAllDownloads()
                downloaded.forEach { repository.markDownloaded(it.id, false) }
            }.onSuccess {
                downloadStates.refreshChapterCacheStates(downloaded.map { it.url })
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to clear downloads: ${e.message}") }
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

    companion object {
        var defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
        var isUnderTest: Boolean = false
        val coversBackfillAttempted = AtomicBoolean(false)
        private const val BACKFILL_CONCURRENCY = 3
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
