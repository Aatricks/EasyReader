package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
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
    private val libraryRepository: LibraryRepository,
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
                val baseTitle = extractBaseTitle(title, contentType)

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
                        baseTitle = fetchedTitle.trim().ifBlank { url } // EPUB doesn't group, so baseTitle = title
                    )
                } else {
                    // For WEB content, extract baseTitle once and store it
                    val chapterLabel = extractChapterLabel(fetchedTitle) ?: extractChapterLabelFromUrl(url) ?: "Chapter 1"
                    val fullTitle = fetchedTitle.trim().ifBlank { url }
                    val baseTitle = extractBaseTitle(fullTitle, contentType)

                    val addedItem = libraryRepository.addItem(
                        title = fullTitle,
                        url = url.trim(),
                        contentType = contentType,
                        currentChapter = chapterLabel,
                        baseTitle = baseTitle
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
        val itemBaseTitle = item.baseTitle.ifBlank { extractBaseTitle(item.title, ContentType.WEB) }
        
        for (i in 1..maxChapters) {
            try {
                val nextUrl = contentRepository?.incrementChapterUrl(currentUrl) ?: break
                if (nextUrl == currentUrl) break // no more chapters
                // Check if already exists
                if (libraryRepository.getItemByUrl(nextUrl) != null) break
                // Fetch title
                val nextTitle = contentRepository?.fetchTitle(nextUrl) ?: break
                val nextBaseTitle = extractBaseTitle(nextTitle, ContentType.WEB)
                
                // If base title matches (or next is blank/generic), add it
                if (nextBaseTitle.equals(itemBaseTitle, ignoreCase = true) || nextBaseTitle.isBlank()) {
                    val chapterLabel = extractChapterLabel(nextTitle) ?: extractChapterLabelFromUrl(nextUrl) ?: "Chapter ${item.currentChapter.filter { it.isDigit() }.toIntOrNull()?.plus(i) ?: (i + 1)}"
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
     * Extract base title by removing chapter markers
     * Only normalizes WEB content - PDFs/HTML/EPUB keep full titles
     */
    private fun extractBaseTitle(title: String, contentType: ContentType): String {
        // Only normalize WEB content for grouping
        if (contentType != ContentType.WEB) return title
        
        // Remove common chapter markers and trailing content
        val patterns = listOf(
            Regex("""[–—\-:]?\s*(?:chapter|ch|ch\.)\s*\d+.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[–—\-]\s*\d+.*$"""), // "Title - 123" or "Title – 123"
            Regex("""\s*:\s*\d+.*$""") // "Title: 123"
        )
        var normalized = title
        for (pattern in patterns) {
            normalized = normalized.replace(pattern, "").trim()
        }
        return if (normalized.isBlank() || normalized.length < 3) title else normalized
    }

    /**
     * Try to extract a human chapter label from a title string
     */
    private fun extractChapterLabel(title: String?): String? {
        if (title == null) return null
        val regex = Regex("""(chapter|ch|ch\.)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        return match?.let { "Chapter ${it.groupValues[2]}" }
    }

    /**
     * Try to find a numeric chapter in the URL
     */
    private fun extractChapterLabelFromUrl(url: String): String? {
        val patterns = listOf(
            Regex("""chapter[-_/](\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ch[-_/]?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)(?=\.html|\.htm|$)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return "Chapter ${m.groupValues[1]}"
        }
        return null
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
     * Clear entire library
     */
    fun clearLibrary() {
        viewModelScope.launch {
            try {
                libraryRepository.clearLibrary()
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
