package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.util.TextUtils
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.data.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the library screen.
 * Manages library items, selection mode, filtering, and search.
 */
class LibraryViewModel(
    val repository: LibraryRepository,
    private val contentRepository: ContentRepository? = null
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filter
    private val _contentTypeFilter = MutableStateFlow<ContentType?>(null)
    val contentTypeFilter: StateFlow<ContentType?> = _contentTypeFilter.asStateFlow()

    // Sort mode
    private val _sortMode = MutableStateFlow(SortMode.LAST_READ)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    // Expose repository for legacy access (e.g. from ExploreScreen)
    val libraryRepository: LibraryRepository get() = repository

    init {
        observeLibraryChanges()
    }

    /**
     * Data class representing the library UI state
     */
    data class LibraryUiState(
        val items: List<LibraryItem> = emptyList(),
        val filteredItems: List<LibraryItem> = emptyList(),
        val groupedItems: Map<String, List<LibraryItem>> = emptyMap(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val isSelectionMode: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val selectedCount: Int = 0,
        val isEmpty: Boolean = true,
        val currentlyReading: LibraryItem? = null
    )

    /**
     * Sort modes for library items
     */
    enum class SortMode {
        LAST_READ,
        DATE_ADDED,
        TITLE,
        PROGRESS
    }

    /**
     * Observe library changes and update UI state
     */
    private fun observeLibraryChanges() {
        viewModelScope.launch {
            combine(
                libraryRepository.libraryItems,
                libraryRepository.selectedItems,
                _searchQuery,
                _contentTypeFilter,
                _sortMode
            ) { items, selectedIds, query, filter, sort ->
                // Apply filters
                var filteredItems = items

                // Filter by content type
                if (filter != null) {
                    filteredItems = libraryRepository.filterByContentType(filter)
                }

                // Apply search query
                if (query.isNotBlank()) {
                    filteredItems = libraryRepository.searchItems(query)
                }

                // Apply sorting
                filteredItems = when (sort) {
                    SortMode.LAST_READ -> filteredItems.sortedByDescending { it.lastRead }
                    SortMode.DATE_ADDED -> filteredItems.sortedByDescending { it.dateAdded }
                    SortMode.TITLE -> filteredItems.sortedBy { it.title.lowercase() }
                    SortMode.PROGRESS -> filteredItems.sortedByDescending { it.progress }
                }

                // Update UI state
                _uiState.update { state ->
                    state.copy(
                        items = items,
                        filteredItems = filteredItems,
                        groupedItems = libraryRepository.getGroupedByTitle(),
                        isSelectionMode = selectedIds.isNotEmpty(),
                        selectedIds = selectedIds,
                        selectedCount = selectedIds.size,
                        isEmpty = items.isEmpty(),
                        currentlyReading = libraryRepository.getCurrentlyReading()
                    )
                }
            }.collect {}
        }
    }

    /**
     * Add a new item to the library
     * @param title Title of the novel/document
     * @param url URL or file path
     * @param contentType Type of content (WEB, PDF, HTML)
     * @param currentChapter Optional starting chapter name
     */
    fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1"
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                // Check if URL already exists
                val existingItem = libraryRepository.getItemByUrl(url)
                if (existingItem != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "This item already exists in your library"
                        )
                    }
                    return@launch
                }

                // Extract baseTitle for grouping
                val baseTitle = TextUtils.extractBaseTitle(title, contentType)

                libraryRepository.addItem(
                    title = title.trim(),
                    url = url.trim(),
                    contentType = contentType,
                    currentChapter = currentChapter,
                    baseTitle = baseTitle
                )

                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to add item: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Add an item from Explore screen, resolving proper reading URL first.
     */
    fun addExploreItem(item: io.aatricks.novelscraper.data.model.ExploreItem, exploreRepository: io.aatricks.novelscraper.data.repository.ExploreRepository) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                // If item already has a resolved reading URL (unlikely from list, but possible from details), use it.
                // Otherwise resolve it.
                val readingUrl = item.readingUrl ?: run {
                    val details = exploreRepository.getNovelDetails(item.url, item.source)
                    details?.readingUrl ?: item.url
                }

                // Now proceed with normal fetchAndAdd logic using the reading URL
                // We use readingUrl as the main URL for the library item.
                // But we might want to preserve the book title if readingUrl is just "Chapter 1"

                // Let's call a modified internal add logic or just use fetchAndAdd but pass the reading URL.
                // However, fetchAndAdd will fetch the title from readingUrl (e.g. "Chapter 1...").
                // We want the book title as the base.

                // So:
                // 1. Check if readingUrl exists in library.
                // 2. Add item using item.title as baseTitle and title.

                val existing = libraryRepository.getItemByUrl(readingUrl)
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
                    // For web, readingUrl is likely a chapter.
                    // We want the library item title to be "Book Title - Chapter X" or just "Book Title" if we group?
                    // LibraryRepository groups by baseTitle.
                    // So we add the item with:
                    // title = "Book Title - Chapter X" (we need to fetch chapter title)
                    // baseTitle = "Book Title"

                    // Let's fetch the chapter title to be nice.
                    val chapterTitle = contentRepository?.fetchTitle(readingUrl) ?: "Chapter 1"
                    // If chapterTitle is just "Chapter 1", combine with book title.
                    // If chapterTitle is "Book Title - Chapter 1", use as is.

                    val fullTitle = if (chapterTitle.contains(item.title, ignoreCase = true)) {
                        chapterTitle
                    } else {
                        "${item.title} - $chapterTitle"
                    }

                    libraryRepository.addItem(
                        title = fullTitle,
                        url = readingUrl,
                        contentType = ContentType.WEB,
                        currentChapter =TextUtils.extractChapterLabel(chapterTitle) ?: "Chapter 1",
                        baseTitle = item.title,
                        baseNovelUrl = item.url,
                        sourceName = item.source,
                        totalChapters = item.chapterCount
                    )

                    // Try to add next chapters
                    // We need a temporary item to pass to addNextChapters
                    val tempItem = libraryRepository.getItemByUrl(readingUrl)
                    if (tempItem != null) {
                        // Automatically adding chapters removed to prevent "starting at ch 6" issue
                        // User can download more if needed.
                    }
                } else {
                    // EPUB/PDF
                    libraryRepository.addItem(
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

    /**
     * Add multiple chapters to the library with consistent metadata for grouping.
     */
    fun addChapters(
        chapters: List<io.aatricks.novelscraper.data.model.ChapterInfo>,
        baseTitle: String,
        baseNovelUrl: String,
        sourceName: String
    ) {
        viewModelScope.launch {
            chapters.forEach { chapter ->
                try {
                    // Check if URL already exists
                    if (libraryRepository.getItemByUrl(chapter.url) == null) {
                        libraryRepository.addItem(
                            title = chapter.title,
                            url = chapter.url,
                            contentType = ContentType.WEB,
                            currentChapter =TextUtils.extractChapterLabel(chapter.title) ?: TextUtils.extractChapterLabelFromUrl(chapter.url) ?: chapter.title,
                            baseTitle = baseTitle,
                            baseNovelUrl = baseNovelUrl,
                            sourceName = sourceName
                        )
                        // Prefetch content to cache it for offline use
                        contentRepository?.prefetch(chapter.url)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Fetch title asynchronously then add item to library. Falls back to URL if title not found.
     * For WEB content, also try to add next chapters.
     * For EPUB content, parse structure and add to library with TOC.
     */
    fun fetchAndAdd(url: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                // If item exists, short-circuit
                val existing = libraryRepository.getItemByUrl(url)
                if (existing != null) {
                    _uiState.update { it.copy(isLoading = false, error = "This item already exists in your library") }
                    return@launch
                }

                // Detect content type
                val contentType = when {
                    url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> ContentType.EPUB
                    url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") -> ContentType.PDF
                    url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> ContentType.HTML
                    url.startsWith("http://") || url.startsWith("https://") -> ContentType.WEB
                    else -> {
                        // For content:// URIs, try to detect from URL string
                        when {
                            url.contains("epub", ignoreCase = true) -> ContentType.EPUB
                            url.contains("pdf", ignoreCase = true) -> ContentType.PDF
                            url.contains("html", ignoreCase = true) -> ContentType.HTML
                            else -> ContentType.WEB
                        }
                    }
                }

                // Fetch title based on content type
                val fetchedTitle = try {
                    contentRepository?.fetchTitle(url) ?: url
                } catch (e: Exception) {
                    url
                }

                if (contentType == ContentType.EPUB) {
                    // For EPUB, add a single entry (baseTitle = title since no grouping needed)
                    libraryRepository.addItem(
                        title = fetchedTitle.trim().ifBlank { url },
                        url = url.trim(),
                        contentType = ContentType.EPUB,
                        currentChapter = "Chapter 1",
                        baseTitle = fetchedTitle.trim().ifBlank { url }, // EPUB doesn't group, so baseTitle = title
                        baseNovelUrl = url,
                        sourceName = "EPUB"
                    )
                } else {
                    // For WEB content, extract baseTitle once and store it
                    val chapterLabel = TextUtils.extractChapterLabel(fetchedTitle) ?: TextUtils.extractChapterLabelFromUrl(fetchedTitle) ?: "Chapter 1"
                    val fullTitle = fetchedTitle.trim().ifBlank { url }
                    val baseTitle = TextUtils.extractBaseTitle(fullTitle, contentType)

                    val addedItem = libraryRepository.addItem(
                        title = fullTitle,
                        url = url.trim(),
                        contentType = contentType,
                        currentChapter = chapterLabel,
                        baseTitle = baseTitle,
                        baseNovelUrl = url, // Best effort if added directly
                        sourceName = "Web"
                    )
                }

                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to add item: ${e.message}") }
            }
        }
    }

    /**
     * Prefetch items in library (or selected) to cache HTML content.
     * For WEB items, also try to add next chapters.
     */
    fun prefetchLibrary(selectedOnly: Boolean = false) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val items = if (selectedOnly) libraryRepository.getSelectedItems() else libraryRepository.libraryItems.value
                items.forEach { item ->
                    try {
                        contentRepository?.prefetch(item.url)
                        // For WEB items, try to add next chapters
                        if (item.contentType == ContentType.WEB) {
                            addNextChapters(item, maxChapters = 10)
                        }
                    } catch (_: Exception) {}
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Prefetch failed: ${e.message}") }
            }
        }
    }

    /**
     * Try to add next chapters for a WEB item by incrementing URL.
     */
    private suspend fun addNextChapters(item: LibraryItem, maxChapters: Int) {
        var currentUrl = item.url
        // Use the item's baseTitle - it's already been extracted
        val itemBaseTitle = item.baseTitle.ifBlank {TextUtils.extractBaseTitle(item.title, ContentType.WEB) }

        for (i in 1..maxChapters) {
            try {
                val nextUrl = contentRepository?.incrementChapterUrl(currentUrl) ?: break
                if (nextUrl == currentUrl) break // no more chapters
                // Check if already exists
                if (libraryRepository.getItemByUrl(nextUrl) != null) break
                // Fetch title
                val nextTitle = contentRepository?.fetchTitle(nextUrl) ?: break
                val nextBaseTitle =TextUtils.extractBaseTitle(nextTitle, ContentType.WEB)

                // If base title matches (or next is blank/generic), add it
                if (nextBaseTitle.equals(itemBaseTitle, ignoreCase = true) || nextBaseTitle.isBlank()) {
                    val chapterLabel =TextUtils.extractChapterLabel(nextTitle) ?: TextUtils.extractChapterLabelFromUrl(nextUrl) ?: "Chapter ${item.currentChapter.filter { it.isDigit() }.toIntOrNull()?.plus(i) ?: (i + 1)}"
                    val fullTitle = nextTitle.trim().ifBlank { "$itemBaseTitle - Chapter ${chapterLabel.replace("Chapter ", "")}" }

                    libraryRepository.addItem(
                        title = fullTitle,
                        url = nextUrl,
                        contentType = ContentType.WEB,
                        currentChapter = chapterLabel,
                        baseTitle = itemBaseTitle
                    )
                    currentUrl = nextUrl
                } else {
                    break // title changed, probably end of series
                }
            } catch (_: Exception) {
                break
            }
        }
    }
    
    /**
     * Remove a single item from library
     */
    fun removeItem(itemId: String) {
        viewModelScope.launch {
            try {
                // Try to clear cached content for this item (best-effort)
                try {
                    val item = libraryRepository.getItemById(itemId)
                    if (item != null) {
                        contentRepository?.clearCache(item.url)
                    }
                } catch (_: Exception) {}

                libraryRepository.removeItem(itemId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove item: ${e.message}")
                }
            }
        }
    }

    /**
     * Remove multiple items from library by their IDs
     */
    fun removeItems(itemIds: Set<String>) {
        viewModelScope.launch {
            try {
                // Clear cache for each item (best-effort)
                itemIds.forEach { id ->
                    try {
                        val item = libraryRepository.getItemById(id)
                        if (item != null) {
                            contentRepository?.clearCache(item.url)
                        }
                    } catch (_: Exception) {}
                }

                libraryRepository.removeItems(itemIds)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove items: ${e.message}")
                }
            }
        }
    }

    /**
     * Remove all items in a group by base title
     */
    fun removeGroup(baseTitle: String) {
        viewModelScope.launch {
            try {
                val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
                if (groupItems.isNotEmpty()) {
                    // Clear cache for each item (best-effort)
                    groupItems.forEach { item ->
                        try {
                            contentRepository?.clearCache(item.url)
                        } catch (_: Exception) {}
                    }

                    val ids = groupItems.map { it.id }.toSet()
                    libraryRepository.removeItems(ids)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove group: ${e.message}")
                }
            }
        }
    }

    /**
     * Update an existing library item
     */
    fun updateItem(item: LibraryItem) {
        viewModelScope.launch {
            try {
                libraryRepository.updateItem(item)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to update item: ${e.message}")
                }
            }
        }
    }

    /**
     * Update reading progress for an item
     */
    fun updateProgress(itemId: String, currentChapter: String, progress: Int) {
        viewModelScope.launch {
            try {
                libraryRepository.updateProgress(itemId, currentChapter, progress)
            } catch (e: Exception) {
                // Silently fail progress updates
            }
        }
    }

    /**
     * Mark an item as currently reading
     */
    fun markAsCurrentlyReading(itemId: String) {
        viewModelScope.launch {
            try {
                libraryRepository.markAsCurrentlyReading(itemId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to mark item: ${e.message}")
                }
            }
        }
    }

    /**
     * Get item by ID
     */
    fun getItemById(itemId: String): LibraryItem? {
        return libraryRepository.getItemById(itemId)
    }

    // Selection Mode Methods

    /**
     * Toggle selection for an item
     */
    fun toggleSelection(itemId: String) {
        libraryRepository.toggleSelection(itemId)
    }

    /**
     * Select an item
     */
    fun selectItem(itemId: String) {
        libraryRepository.selectItem(itemId)
    }

    /**
     * Deselect an item
     */
    fun deselectItem(itemId: String) {
        libraryRepository.deselectItem(itemId)
    }

    /**
     * Select all items in a group
     */
    fun selectGroup(baseTitle: String) {
        viewModelScope.launch {
            val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
            val itemIds = groupItems.map { it.id }
            libraryRepository.selectItems(itemIds)
        }
    }

    /**
     * Deselect all items in a group
     */
    fun deselectGroup(baseTitle: String) {
        viewModelScope.launch {
            val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
            val itemIds = groupItems.map { it.id }
            libraryRepository.deselectItems(itemIds)
        }
    }

    /**
     * Toggle selection for all items in a group
     * If all are selected, deselect all. Otherwise, select all.
     */
    fun toggleGroupSelection(baseTitle: String) {
        viewModelScope.launch {
            val groupItems = uiState.value.groupedItems[baseTitle] ?: emptyList()
            val selectedIds = uiState.value.selectedIds
            val allSelected = groupItems.all { it.id in selectedIds }
            val itemIds = groupItems.map { it.id }

            if (allSelected) {
                libraryRepository.deselectItems(itemIds)
            } else {
                libraryRepository.selectItems(itemIds)
            }
        }
    }

    /**
     * Check if a group is fully selected
     */
    fun isGroupSelected(baseTitle: String): Boolean {
        val groupItems = uiState.value.groupedItems[baseTitle] ?: return false
        if (groupItems.isEmpty()) return false
        val selectedIds = uiState.value.selectedIds
        return groupItems.all { it.id in selectedIds }
    }

    /**
     * Select all items
     */
    fun selectAll() {
        libraryRepository.selectAll()
    }

    /**
     * Clear all selections
     */
    fun clearSelection() {
        libraryRepository.clearSelection()
    }

    /**
     * Check if item is selected
     */
    fun isSelected(itemId: String): Boolean {
        return libraryRepository.isSelected(itemId)
    }

    /**
     * Exit selection mode
     */
    fun exitSelectionMode() {
        libraryRepository.clearSelection()
    }

    // Search and Filter Methods

    /**
     * Update search query
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Clear search query
     */
    fun clearSearch() {
        _searchQuery.value = ""
    }

    /**
     * Set content type filter
     */
    fun setContentTypeFilter(contentType: ContentType?) {
        _contentTypeFilter.value = contentType
    }

    /**
     * Clear content type filter
     */
    fun clearFilter() {
        _contentTypeFilter.value = null
    }

    /**
     * Set sort mode
     */
    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    // Library Management Methods

    /**
     * Reload library from storage
     */
    fun reload() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                libraryRepository.reload()
                _uiState.update { it.copy(isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to reload library: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Remove all currently selected items from library
     */
    fun removeSelectedItems() {
        viewModelScope.launch {
            try {
                val selectedItems = libraryRepository.getSelectedItems()
                if (selectedItems.isNotEmpty()) {
                    // Clear cache for each item (best-effort)
                    selectedItems.forEach { item ->
                        try {
                            contentRepository?.clearCache(item.url)
                        } catch (_: Exception) {}
                    }

                    val ids = selectedItems.map { it.id }.toSet()
                    libraryRepository.removeItems(ids)
                    libraryRepository.clearSelection()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove selected items: ${e.message}")
                }
            }
        }
    }

    /**
     * Clear entire library
     */
    fun clearLibrary() {
        viewModelScope.launch {
            try {
                libraryRepository.clearLibrary()
                contentRepository?.clearAllCache()
                _uiState.update { it.copy(error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to clear library: ${e.message}")
                }
            }
        }
    }

    /**
     * Get library statistics
     */
    fun getStatistics(): LibraryRepository.LibraryStatistics {
        return libraryRepository.getStatistics()
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Check if library is empty
     */
    fun isEmpty(): Boolean {
        return _uiState.value.isEmpty
    }

    /**
     * Get filtered items count
     */
    fun getFilteredItemsCount(): Int {
        return _uiState.value.filteredItems.size
    }

    /**
     * Get total items count
     */
    fun getTotalItemsCount(): Int {
        return _uiState.value.items.size
    }

    /**
     * Check if filters are active
     */
    fun hasActiveFilters(): Boolean {
        return _searchQuery.value.isNotBlank() || _contentTypeFilter.value != null
    }

    /**
     * Clear all filters and search
     */
    fun clearAllFilters() {
        _searchQuery.value = ""
        _contentTypeFilter.value = null
    }

    /**
     * Update chapter summary for a specific item
     * @param itemId The library item ID
     * @param chapterUrl The chapter URL
     * @param summary The AI-generated summary
     */
    fun updateChapterSummary(itemId: String, chapterUrl: String, summary: String) {
        viewModelScope.launch {
            try {
                val item = libraryRepository.getItemById(itemId)
                if (item != null) {
                    val updatedSummaries = (item.chapterSummaries ?: emptyMap()).toMutableMap()
                    updatedSummaries[chapterUrl] = summary
                    val updatedItem = item.copy(chapterSummaries = updatedSummaries)
                    libraryRepository.updateItem(updatedItem)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to save summary: ${e.message}")
                }
            }
        }
    }
}
