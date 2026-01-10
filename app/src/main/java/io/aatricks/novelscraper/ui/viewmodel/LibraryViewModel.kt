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
    private val contentRepository: ContentRepository
) : ViewModel() {

    private val TAG = "LibraryViewModel"

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

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

    private fun observeLibraryChanges() {
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
                val items = repoData.first
                val selectedIds = repoData.second
                val collapsedSources = repoData.third
                
                var filteredItems = items

                if (filter != null) {
                    filteredItems = filteredItems.filter { it.contentType == filter }
                }

                if (query.isNotBlank()) {
                    val lowercaseQuery = query.trim().lowercase()
                    filteredItems = filteredItems.filter {
                        it.title.lowercase().contains(lowercaseQuery) ||
                        it.baseTitle.lowercase().contains(lowercaseQuery)
                    }
                }

                filteredItems = when (sort) {
                    SortMode.LAST_READ -> filteredItems.sortedByDescending { it.lastRead }
                    SortMode.DATE_ADDED -> filteredItems.sortedByDescending { it.dateAdded }
                    SortMode.TITLE -> filteredItems.sortedBy { it.title.lowercase() }
                    SortMode.PROGRESS -> filteredItems.sortedByDescending { it.progress }
                }

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
                _uiState.value = newState
            }
        }
    }

    fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1"
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val existingItem = repository.getItemByUrl(url)
                if (existingItem != null) {
                    _uiState.update { it.copy(isLoading = false, error = "This item already exists in your library") }
                    return@launch
                }
                val baseTitle = TextUtils.extractBaseTitle(title, contentType)
                repository.addItem(
                    title = title.trim(),
                    url = url.trim(),
                    contentType = contentType,
                    currentChapter = currentChapter,
                    baseTitle = baseTitle
                )
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
    }

    fun addExploreItem(item: io.aatricks.novelscraper.data.model.ExploreItem, exploreRepository: ExploreRepository) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val readingUrl = item.readingUrl ?: run {
                    val details = exploreRepository.getNovelDetails(item.url, item.source)
                    details?.readingUrl ?: item.url
                }
                val existing = repository.getItemByUrl(readingUrl)
                if (existing != null) {
                    _uiState.update { it.copy(isLoading = false, error = "Item already in library") }
                    return@launch
                }
                val contentType = when {
                    readingUrl.endsWith(".epub", ignoreCase = true) -> ContentType.EPUB
                    readingUrl.endsWith(".pdf", ignoreCase = true) -> ContentType.PDF
                    else -> ContentType.WEB
                }
                if (contentType == ContentType.WEB) {
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
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to add: ${e.message}") }
            }
        }
    }

    fun addChapters(
        chapters: List<io.aatricks.novelscraper.data.model.ChapterInfo>,
        baseTitle: String,
        baseNovelUrl: String,
        sourceName: String
    ) {
        viewModelScope.launch {
            chapters.forEach { chapter ->
                try {
                    if (repository.getItemByUrl(chapter.url) == null) {
                        repository.addItem(
                            title = chapter.title,
                            url = chapter.url,
                            contentType = ContentType.WEB,
                            currentChapter = TextUtils.extractChapterLabel(chapter.title) ?: TextUtils.extractChapterLabelFromUrl(chapter.url) ?: chapter.title,
                            baseTitle = baseTitle,
                            baseNovelUrl = baseNovelUrl,
                            sourceName = sourceName
                        )
                        contentRepository.prefetch(chapter.url)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun fetchAndAdd(url: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val existing = repository.getItemByUrl(url)
                if (existing != null) {
                    _uiState.update { it.copy(isLoading = false, error = "This item already exists in your library") }
                    return@launch
                }
                val contentType = when {
                    url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> ContentType.EPUB
                    url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") -> ContentType.PDF
                    url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> ContentType.HTML
                    url.startsWith("http://") || url.startsWith("https://") -> ContentType.WEB
                    else -> {
                        when {
                            url.contains("epub", ignoreCase = true) -> ContentType.EPUB
                            url.contains("pdf", ignoreCase = true) -> ContentType.PDF
                            url.contains("html", ignoreCase = true) -> ContentType.HTML
                            else -> ContentType.WEB
                        }
                    }
                }
                val fetchedTitle = try {
                    contentRepository.fetchTitle(url) ?: url
                } catch (e: Exception) {
                    url
                }
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
                    val chapterLabel = TextUtils.extractChapterLabel(fetchedTitle) ?: TextUtils.extractChapterLabelFromUrl(fetchedTitle) ?: "Chapter 1"
                    val fullTitle = fetchedTitle.trim().ifBlank { url }
                    val baseTitle = TextUtils.extractBaseTitle(fullTitle, contentType)
                    repository.addItem(
                        title = fullTitle,
                        url = url.trim(),
                        contentType = contentType,
                        currentChapter = chapterLabel,
                        baseTitle = baseTitle,
                        baseNovelUrl = url,
                        sourceName = "Web"
                    )
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
    }

    fun prefetchLibrary(selectedOnly: Boolean = false) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val items = if (selectedOnly) repository.getSelectedItems() else repository.libraryItems.value
                items.forEach { item ->
                    try {
                        contentRepository.prefetch(item.url)
                    } catch (_: Exception) {}
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Prefetch failed: ${e.message}") }
            }
        }
    }
    
    fun removeItem(itemId: String) {
        viewModelScope.launch {
            try {
                val item = repository.getItemById(itemId)
                if (item != null) {
                    contentRepository.clearCache(item.url)
                }
                repository.removeItem(itemId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to remove item: ${e.message}") }
            }
        }
    }

    fun removeItems(itemIds: Set<String>) {
        viewModelScope.launch {
            try {
                itemIds.forEach { id ->
                    val item = repository.getItemById(id)
                    if (item != null) {
                        contentRepository.clearCache(item.url)
                    }
                }
                repository.removeItems(itemIds)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to remove items: ${e.message}") }
            }
        }
    }

    fun removeGroup(baseTitle: String) {
        viewModelScope.launch {
            try {
                val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
                if (groupItems.isNotEmpty()) {
                    groupItems.forEach { item ->
                        contentRepository.clearCache(item.url)
                    }
                    val ids = groupItems.map { it.id }.toSet()
                    repository.removeItems(ids)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to remove group: ${e.message}") }
            }
        }
    }

    fun updateItem(item: LibraryItem) {
        viewModelScope.launch {
            try {
                repository.updateItem(item)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update item: ${e.message}") }
            }
        }
    }

    fun updateProgress(itemId: String, currentChapter: String, progress: Int) {
        viewModelScope.launch {
            try {
                repository.updateProgress(itemId, currentChapter, progress)
            } catch (_: Exception) {}
        }
    }

    fun markAsCurrentlyReading(itemId: String) {
        viewModelScope.launch {
            try {
                repository.markAsCurrentlyReading(itemId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to mark item: ${e.message}") }
            }
        }
    }

    fun toggleSelection(itemId: String) {
        repository.toggleSelection(itemId)
    }

    fun selectItem(itemId: String) {
        repository.selectItem(itemId)
    }

    fun deselectItem(itemId: String) {
        repository.deselectItem(itemId)
    }

    fun toggleGroupSelection(baseTitle: String) {
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

    fun selectAll() {
        repository.selectAll()
    }

    fun clearSelection() {
        repository.clearSelection()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setContentTypeFilter(contentType: ContentType?) {
        _contentTypeFilter.value = contentType
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun removeSelectedItems() {
        viewModelScope.launch {
            try {
                val selectedIds = repository.selectedItems.value
                val selectedItems = repository.libraryItems.value.filter { it.id in selectedIds }
                if (selectedItems.isNotEmpty()) {
                    selectedItems.forEach { item ->
                        contentRepository.clearCache(item.url)
                    }
                    repository.removeItems(selectedIds)
                    repository.clearSelection()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to remove selected items: ${e.message}") }
            }
        }
    }

    fun clearLibrary() {
        viewModelScope.launch {
            try {
                repository.clearLibrary()
                contentRepository.clearAllCache()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to clear library: ${e.message}") }
            }
        }
    }

    fun toggleSourceExpansion(sourceName: String) {
        repository.toggleSourceExpansion(sourceName)
    }
}