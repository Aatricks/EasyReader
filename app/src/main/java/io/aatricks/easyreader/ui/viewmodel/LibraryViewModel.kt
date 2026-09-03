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
import io.aatricks.easyreader.ui.screens.library.FLAT_LIBRARY_SECTION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.resolvedChapterNumber
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
    private val _groupBySource = MutableStateFlow(false)
    val groupBySource: StateFlow<Boolean> = _groupBySource.asStateFlow()
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

    /**
     * Per-chapter badge state. Deliberately NOT part of [uiState]: a download progress tick must not
     * re-run the whole-library filter/group/sort that the ui state combine performs.
     */
    val chapterCacheStates: StateFlow<Map<String, PrefetchResult>> = downloadStates.chapterCacheStates

    /** Chapters whose queued download ended in failure; cleared when the download is re-queued. */
    val downloadFailures: StateFlow<Set<String>> = downloadStates.downloadFailures

    val downloadRetryPrompt: StateFlow<DownloadRetryPrompt?> = downloadStates.retryPrompt

    private val _openNextChapterState = MutableStateFlow<OpenNextChapterState>(OpenNextChapterState.Idle)
    val openNextChapterState: StateFlow<OpenNextChapterState> = _openNextChapterState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        _collapsedSources.value = repository.loadCollapsedSources()
        _groupBySource.value = repository.loadGroupBySource()
    }

    /**
     * Fetches covers for library novels that have none. Screen-triggered rather than run from
     * `init`: the ViewModel is also created off the reader's drawer, where a fan-out of novel-detail
     * fetches has no visible payoff. Still at most once per process.
     */
    fun backfillMissingCovers(): Unit {
        if (coversBackfillAttempted.compareAndSet(false, true)) {
            viewModelScope.launch(defaultDispatcher) {
                runCatching { backfillCovers(repository, exploreRepository) }
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
            deletionCoordinator.pendingDeletion,
            filters.statusFilter
        ) { p, s ->
            p to s
        },
        combine(
            filters.searchQuery,
            filters.contentTypeFilter,
            filters.sortMode,
            _groupBySource
        ) { query, filter, sort, groupBySource ->
            FilterParams(query, filter, sort, groupBySource)
        },
        _uiState
    ) { repoState, pendingStatus, filterParams, manualUiState ->
        val (repoData, selectionModeEnabled) = repoState
        val (rawItems, selectedIds, collapsedSources) = repoData
        val (pendingIds, statusFilter) = pendingStatus

        val items = if (pendingIds.isEmpty()) rawItems else rawItems.filterNot { it.id in pendingIds }
        val filteredItems = filters.apply(
            items, filterParams.query, filterParams.contentType, filterParams.sort, statusFilter
        )
        val groupedItems = repository.getGroupedByTitle(filteredItems)

        LibraryUiState(
            items = items,
            filteredItems = filteredItems,
            groupedItems = groupedItems,
            // Flat list keeps the sort order across sources; sections only when the user asks.
            groupedBySource = if (filterParams.groupBySource) {
                repository.getGroupedBySourceAndTitle(filteredItems)
            } else {
                mapOf(FLAT_LIBRARY_SECTION to groupedItems)
            },
            groupBySource = filterParams.groupBySource,
            sortMode = filterParams.sort,
            collapsedSources = collapsedSources,
            isSelectionMode = selectionModeEnabled || selectedIds.isNotEmpty(),
            selectedIds = selectedIds,
            selectedCount = selectedIds.size,
            isEmpty = items.isEmpty(),
            currentlyReading = items.find { it.isCurrentlyReading },
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

    private data class FilterParams(
        val query: String,
        val contentType: ContentType?,
        val sort: SortMode,
        val groupBySource: Boolean
    )

    data class LibraryUiState(
        val items: List<LibraryItem> = emptyList(),
        val filteredItems: List<LibraryItem> = emptyList(),
        val groupedItems: Map<String, List<LibraryItem>> = emptyMap(),
        val groupedBySource: Map<String, Map<String, List<LibraryItem>>> = emptyMap(),
        val groupBySource: Boolean = false,
        val sortMode: SortMode = SortMode.LAST_READ,
        val collapsedSources: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val snackbarMessage: String? = null,
        val isSelectionMode: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val selectedCount: Int = 0,
        val isEmpty: Boolean = true,
        val currentlyReading: LibraryItem? = null
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

    private class AlreadyInLibraryException : Exception("Item already in library")

    /**
     * Adds [item] and hands the outcome back so the calling screen can say what happened on its
     * own snackbar: success carries true when the item was written and false when the resolved
     * reading URL was already in the library. Runs on [viewModelScope], so a caller that goes
     * away mid-add does not abort a half-finished write.
     */
    suspend fun addExploreItem(item: ExploreItem): Result<Boolean> =
        viewModelScope.async {
            updateState { it.copy(isLoading = true) }
            val outcome = runCatching { addExploreItemInternal(item) }.fold(
                onSuccess = { Result.success(true) },
                onFailure = { e ->
                    if (e is AlreadyInLibraryException) Result.success(false) else Result.failure(e)
                }
            )
            updateState { it.copy(isLoading = false) }
            outcome
        }.await()

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
            throw AlreadyInLibraryException()
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

    /**
     * Open the chapter that follows [item] (the row carrying the "new chapter" badge), so a user
     * who stopped at 213 lands on 214 even if 215-217 were released since the badge appeared.
     */
    fun openNewChapter(
        item: LibraryItem,
        onChapterLoaded: (String, String) -> Unit
    ): Unit {
        if (openNewChapterJob?.isActive == true) return
        openNewChapterJob = viewModelScope.launch {
            runCatching {
                _openNextChapterState.value = OpenNextChapterState.Loading
                val details = exploreRepository.getNovelDetails(item.baseNovelUrl, item.sourceName)
                val normalizedChapters = normalizeChapterList(details?.chapters.orEmpty())
                if (details == null || normalizedChapters.isEmpty()) {
                    throw Exception("No chapters found for this novel")
                }

                val nextChapter = selectNextChapter(normalizedChapters, item.resolvedChapterNumber())
                    ?: throw Exception("No latest chapter found for this novel")
                val target = adoptChapterIntoSeries(repository, item, nextChapter, normalizedChapters.size)

                repository.clearUpdateIndicator(item.id)
                onChapterLoaded(target.url, target.id)
                _openNextChapterState.value = OpenNextChapterState.Idle
            }.rethrowCancellation().onFailure { e ->
                Log.e(TAG, "Failed to open new chapter", e)
                _openNextChapterState.value =
                    OpenNextChapterState.Error("Failed to load new chapter: ${e.message}")
            }
        }
    }

    fun consumeOpenNextChapterError(): Unit {
        if (_openNextChapterState.value is OpenNextChapterState.Error) {
            _openNextChapterState.value = OpenNextChapterState.Idle
        }
    }

    /**
     * Pull-to-refresh: check the sources for new chapters (the same refresh the periodic
     * [io.aatricks.easyreader.work.LibraryUpdateWorker] runs), then re-verify download state.
     * [isRefreshing] stays true for the whole check so the indicator doesn't flash.
     */
    fun refreshUpdates(): Unit {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching {
                repository.refreshLibraryUpdates(exploreRepository, ignoreActivityThreshold = true)
            }.onFailure { e ->
                updateState { it.copy(error = "Update check failed: ${e.message}") }
            }
            reconcileDownloadedItemsOnDemand()
            _isRefreshing.value = false
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

    fun retryDownloads(urls: List<String>): Unit {
        urls.forEach { retryDownload(it) }
    }

    fun consumeDownloadRetryPrompt(): Unit {
        downloadStates.consumeRetryPrompt()
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
            downloadStates.removeDownload(item)
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

    fun setGroupBySource(enabled: Boolean): Unit {
        _groupBySource.value = enabled
        repository.saveGroupBySource(enabled)
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
                downloaded.forEach { item ->
                    downloadStatusReconciler.reconcile(
                        item,
                        contentRepository.inspectDownload(item.url),
                        wasUserInspect = true
                    )
                }
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
    }
}

/** One-shot snackbar prompt whose action re-queues [urls]. */
data class DownloadRetryPrompt(
    val message: String,
    val actionLabel: String,
    val urls: List<String>
)

/**
 * Progress/error for "read next" opened from the reader's library drawer. Separate from
 * [LibraryViewModel.LibraryUiState] because the drawer never observes the library screen's state.
 */
sealed interface OpenNextChapterState {
    data object Idle : OpenNextChapterState
    data object Loading : OpenNextChapterState
    data class Error(val message: String) : OpenNextChapterState
}

/**
 * The library row for [nextChapter] of [series], inserting it if the user never opened that chapter,
 * and keeping the stored chapter count in step with the source's current list.
 */
private suspend fun adoptChapterIntoSeries(
    repository: LibraryRepository,
    series: LibraryItem,
    nextChapter: ChapterInfo,
    totalChapters: Int
): LibraryItem {
    val existing = repository.getItemByUrl(nextChapter.url)
    if (existing != null) {
        if (existing.totalChapters < totalChapters) {
            repository.updateItem(existing.copy(totalChapters = totalChapters))
        }
        return existing
    }
    return repository.addItem(
        title = nextChapter.title,
        url = nextChapter.url,
        contentType = ContentType.WEB,
        currentChapter = TextUtils.extractChapterLabel(nextChapter.title)
            ?: TextUtils.extractChapterLabelFromUrl(nextChapter.url)
            ?: nextChapter.title,
        baseTitle = series.libraryDisplayTitle(),
        baseNovelUrl = series.baseNovelUrl,
        sourceName = series.sourceName,
        totalChapters = totalChapters
    )
}

private const val BACKFILL_CONCURRENCY = 3

/** Fetches and stores a cover for every cover-less WEB novel, [BACKFILL_CONCURRENCY] at a time. */
private suspend fun backfillCovers(
    repository: LibraryRepository,
    exploreRepository: ExploreRepository
) = coroutineScope {
    val itemsToBackfill = repository.getAllItemsSnapshot().filter {
        it.coverImageUrl.isBlank() && it.contentType == ContentType.WEB
    }
    if (itemsToBackfill.isEmpty()) return@coroutineScope

    val semaphore = Semaphore(BACKFILL_CONCURRENCY)
    itemsToBackfill.groupBy { it.libraryNovelKey() }.values.map { novelGroup ->
        async {
            semaphore.withPermit {
                val firstItem = novelGroup.first()
                val url = firstItem.baseNovelUrl.ifBlank { firstItem.url }
                val sourceName = firstItem.sourceName
                runCatching {
                    val knownSources = exploreRepository.getSourceNames()
                    val details = if (knownSources.contains(sourceName)) {
                        exploreRepository.getNovelDetails(url, sourceName)
                    } else {
                        exploreRepository.getNovelDetailsByUrl(url)
                    }
                    details?.coverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
                        repository.updateCoverImageUrl(firstItem.libraryDisplayTitle(), sourceName, coverUrl)
                    }
                }
            }
        }
    }.awaitAll()
}

/**
 * The first chapter numbered after [afterNumber] (where the user stopped). Falls back to the
 * newest chapter when the user's position is unknown or nothing newer exists.
 */
internal fun selectNextChapter(chapters: List<ChapterInfo>, afterNumber: Double?): ChapterInfo? {
    if (chapters.isEmpty()) return null

    val numbered = chapters.withIndex().mapNotNull { (index, chapter) ->
        val chapterNumber = chapter.number
            ?: TextUtils.extractChapterNumber(chapter.title)
            ?: TextUtils.extractChapterNumber(chapter.url)
            ?: return@mapNotNull null
        Triple(chapterNumber, index, chapter)
    }
    val order = compareBy<Triple<Double, Int, ChapterInfo>>({ it.first }, { it.second })
    val next = afterNumber?.let { after -> numbered.filter { it.first > after }.minWithOrNull(order) }
    val latest = numbered.maxWithOrNull(order)

    return (next ?: latest)?.third ?: chapters.lastOrNull()
}
