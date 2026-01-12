package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.util.TextUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    init {
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
        val currentlyReading: LibraryItem? = null
    )

    enum class SortMode {
        LAST_READ,
        DATE_ADDED,
        TITLE,
        PROGRESS
    }

    private fun observeLibraryChanges(): Unit {
        viewModelScope.launch {
            val repoFlow = combine(
                repository.libraryItems,
                repository.selectedItems,
                repository.collapsedSources
            ) { items, selected, collapsed ->
                Triple(items, selected, collapsed)
            }

            combine(
                repoFlow,
                _searchQuery,
                _contentTypeFilter,
                _sortMode
            ) { repoData, query, filter, sort ->
                val (items, selectedIds, collapsedSources) = repoData
                
                val filteredItems = filterAndSortItems(items, query, filter, sort)

                LibraryUiState(
                    items = items,
                    filteredItems = filteredItems,
                    groupedItems = repository.getGroupedByTitle(filteredItems),
                    groupedBySource = repository.getGroupedBySourceAndTitle(filteredItems),
                    collapsedSources = collapsedSources,
                    isSelectionMode = selectedIds.isNotEmpty(),
                    selectedIds = selectedIds,
                    selectedCount = selectedIds.size,
                    isEmpty = items.isEmpty(),
                    currentlyReading = items.find { it.isCurrentlyReading }
                )
            }.collect { newState ->
                updateState { newState }
            }
        }
    }

    private fun filterAndSortItems(
        items: List<LibraryItem>,
        query: String,
        filter: ContentType?,
        sort: SortMode
    ): List<LibraryItem> {
        var filtered = items

        if (filter != null) {
            filtered = filtered.filter { it.contentType == filter }
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
        item: io.aatricks.novelscraper.data.model.ExploreItem,
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
        item: io.aatricks.novelscraper.data.model.ExploreItem,
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
        chapters: List<io.aatricks.novelscraper.data.model.ChapterInfo>,
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
                        contentRepository.prefetch(chapter.url)
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
                val contentType = inferContentType(url)
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

    private fun inferContentType(url: String): ContentType {
        return when {
            url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> ContentType.EPUB
            url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") -> ContentType.PDF
            url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> ContentType.HTML
            else -> ContentType.WEB
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
                if (details == null || details.chapters.isEmpty()) {
                    throw Exception("No chapters found for this novel")
                }
                
                val latestChapter = details.chapters.last()
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
                        totalChapters = details.chapters.size
                    )
                    contentRepository.prefetch(latestChapter.url)
                } else if (item.totalChapters < details.chapters.size) {
                    repository.updateItem(item.copy(totalChapters = details.chapters.size))
                }
                
                repository.clearUpdateIndicator(item!!.id)
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
                val items = if (selectedOnly) repository.getSelectedItems() else repository.libraryItems.value
                items.forEach { item ->
                    runCatching { contentRepository.prefetch(item.url) }
                }
                updateState { it.copy(isLoading = false) }
            }.onFailure { e ->
                updateState { it.copy(isLoading = false, error = "Prefetch failed: ${e.message}") }
            }
        }
    }
    
    fun removeItem(itemId: String): Unit {
        viewModelScope.launch {
            runCatching {
                repository.getItemById(itemId)?.let { item ->
                    contentRepository.clearCache(item.url)
                }
                repository.removeItem(itemId)
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to remove item: ${e.message}") }
            }
        }
    }

    fun removeItems(itemIds: Set<String>): Unit {
        viewModelScope.launch {
            runCatching {
                itemIds.forEach { id ->
                    repository.getItemById(id)?.let { item ->
                        contentRepository.clearCache(item.url)
                    }
                }
                repository.removeItems(itemIds)
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to remove items: ${e.message}") }
            }
        }
    }

    fun removeGroup(baseTitle: String): Unit {
        viewModelScope.launch {
            runCatching {
                val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
                if (groupItems.isNotEmpty()) {
                    groupItems.forEach { item ->
                        contentRepository.clearCache(item.url)
                    }
                    val ids = groupItems.map { it.id }.toSet()
                    repository.removeItems(ids)
                }
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to remove group: ${e.message}") }
            }
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
        repository.toggleSelection(itemId)
    }

    fun selectItem(itemId: String): Unit {
        repository.selectItem(itemId)
    }

    fun deselectItem(itemId: String): Unit {
        repository.deselectItem(itemId)
    }

    fun toggleGroupSelection(baseTitle: String): Unit {
        viewModelScope.launch {
            val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
            val selectedIds = uiState.value.selectedIds
            val allSelected = groupItems.all { it.id in selectedIds }
            val itemIds = groupItems.map { it.id }
            
            if (allSelected) {
                repository.deselectItems(itemIds)
            } else {
                repository.selectItems(itemIds)
            }
        }
    }

    fun selectAll(): Unit {
        repository.selectAll()
    }

    fun clearSelection(): Unit {
        repository.clearSelection()
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

    fun removeSelectedItems(): Unit {
        viewModelScope.launch {
            runCatching {
                val selectedIds = repository.selectedItems.value
                val selectedItems = repository.libraryItems.value.filter { it.id in selectedIds }
                if (selectedItems.isNotEmpty()) {
                    selectedItems.forEach { item -> contentRepository.clearCache(item.url) }
                    repository.removeItems(selectedIds)
                    repository.clearSelection()
                }
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to remove selected items: ${e.message}") }
            }
        }
    }

    fun clearLibrary(): Unit {
        viewModelScope.launch {
            runCatching {
                repository.clearLibrary()
                contentRepository.clearAllCache()
            }.onFailure { e ->
                updateState { it.copy(error = "Failed to clear library: ${e.message}") }
            }
        }
    }

    fun toggleSourceExpansion(sourceName: String): Unit {
        repository.toggleSourceExpansion(sourceName)
    }

}