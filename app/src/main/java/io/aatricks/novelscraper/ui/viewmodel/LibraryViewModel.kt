package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.repository.LibraryRepository
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
    private val libraryRepository: LibraryRepository
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

                libraryRepository.addItem(
                    title = title.trim(),
                    url = url.trim(),
                    contentType = contentType,
                    currentChapter = currentChapter
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
     * Remove a single item from library
     */
    fun removeItem(itemId: String) {
        viewModelScope.launch {
            try {
                libraryRepository.removeItem(itemId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove item: ${e.message}")
                }
            }
        }
    }

    /**
     * Remove selected items from library
     */
    fun removeSelectedItems() {
        viewModelScope.launch {
            try {
                val selectedIds = libraryRepository.selectedItems.value
                if (selectedIds.isNotEmpty()) {
                    val removedCount = libraryRepository.removeItems(selectedIds)
                    libraryRepository.clearSelection()
                    
                    _uiState.update {
                        it.copy(error = null)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to remove items: ${e.message}")
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
}
