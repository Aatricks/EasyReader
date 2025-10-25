package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Repository for library management with persistence
 */
class LibraryRepository(private val preferencesManager: PreferencesManager) {
    
    private val _libraryItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val libraryItems: StateFlow<List<LibraryItem>> = _libraryItems.asStateFlow()
    
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()
    
    init {
        // Load library on initialization
        _libraryItems.value = preferencesManager.loadLibraryItems()
    }
    
    /**
     * Add a new item to the library
     */
    suspend fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1",
        baseTitle: String = title // Default to full title if not provided
    ): LibraryItem = withContext(Dispatchers.IO) {
        val newItem = LibraryItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = url,
            currentChapter = currentChapter,
            contentType = contentType,
            dateAdded = System.currentTimeMillis(),
            lastRead = System.currentTimeMillis(),
            isCurrentlyReading = false,
            baseTitle = baseTitle
        )
        
        val currentItems = _libraryItems.value.toMutableList()
        currentItems.add(0, newItem) // Add to top
        _libraryItems.value = currentItems
        saveToPreferences()
        
        newItem
    }
    
    /**
     * Remove item from library
     */
    suspend fun removeItem(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val removed = currentItems.removeIf { it.id == itemId }
        if (removed) {
            _libraryItems.value = currentItems
            saveToPreferences()
        }
        removed
    }
    
    /**
     * Remove multiple items
     */
    suspend fun removeItems(itemIds: Set<String>): Int = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val originalSize = currentItems.size
        currentItems.removeAll { it.id in itemIds }
        val removedCount = originalSize - currentItems.size
        
        if (removedCount > 0) {
            _libraryItems.value = currentItems
            saveToPreferences()
        }
        removedCount
    }
    
    /**
     * Update an existing item
     */
    suspend fun updateItem(updatedItem: LibraryItem): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == updatedItem.id }
        
        if (index != -1) {
            currentItems[index] = updatedItem
            _libraryItems.value = currentItems
            saveToPreferences()
            true
        } else {
            false
        }
    }
    
    /**
     * Update reading progress
     */
    suspend fun updateProgress(
        itemId: String,
        currentChapter: String,
        progress: Int,
        currentChapterUrl: String? = null,
        lastScrollProgress: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == itemId }
        
        if (index != -1) {
            val item = currentItems[index]
            currentItems[index] = item.copy(
                // Only update currentChapter if a non-empty value is provided
                currentChapter = if (currentChapter.isNotBlank()) currentChapter else item.currentChapter,
                progress = progress,
                currentChapterUrl = currentChapterUrl ?: item.currentChapterUrl,
                lastScrollPosition = lastScrollProgress ?: item.lastScrollPosition,
                lastRead = System.currentTimeMillis()
            )
            _libraryItems.value = currentItems
            saveToPreferences()
            true
        } else {
            false
        }
    }
    
    /**
     * Mark item as currently reading (and unmark others)
     */
    suspend fun markAsCurrentlyReading(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        var updated = false
        
        for (i in currentItems.indices) {
            val item = currentItems[i]
            if (item.id == itemId && !item.isCurrentlyReading) {
                currentItems[i] = item.copy(
                    isCurrentlyReading = true,
                    lastRead = System.currentTimeMillis()
                )
                updated = true
            } else if (item.id != itemId && item.isCurrentlyReading) {
                currentItems[i] = item.copy(isCurrentlyReading = false)
                updated = true
            }
        }
        
        if (updated) {
            _libraryItems.value = currentItems
            saveToPreferences()
        }
        updated
    }
    
    /**
     * Get currently reading item
     */
    fun getCurrentlyReading(): LibraryItem? {
        return _libraryItems.value.find { it.isCurrentlyReading }
    }
    
    /**
     * Get item by ID
     */
    fun getItemById(itemId: String): LibraryItem? {
        return _libraryItems.value.find { it.id == itemId }
    }
    
    /**
     * Get item by URL
     */
    fun getItemByUrl(url: String): LibraryItem? {
        return _libraryItems.value.find { it.url == url }
    }
    
    /**
     * Group items by baseTitle
     */
    fun getGroupedByTitle(): Map<String, List<LibraryItem>> {
        // Simply group by baseTitle field - normalization happened at creation time
        return _libraryItems.value.groupBy { item ->
            // Use baseTitle if available, otherwise fall back to title
            item.baseTitle.ifBlank { item.title }
        }
    }
    
    /**
     * Get items sorted by last read
     */
    fun getItemsSortedByLastRead(): List<LibraryItem> {
        return _libraryItems.value.sortedByDescending { it.lastRead }
    }
    
    /**
     * Get items sorted by date added
     */
    fun getItemsSortedByDateAdded(): List<LibraryItem> {
        return _libraryItems.value.sortedByDescending { it.dateAdded }
    }
    
    /**
     * Get items sorted by title
     */
    fun getItemsSortedByTitle(): List<LibraryItem> {
        return _libraryItems.value.sortedBy { it.title.lowercase() }
    }
    
    /**
     * Get items sorted by progress
     */
    fun getItemsSortedByProgress(): List<LibraryItem> {
        return _libraryItems.value.sortedByDescending { it.progress }
    }
    
    /**
     * Search items by title or chapter
     */
    fun searchItems(query: String): List<LibraryItem> {
        if (query.isBlank()) return _libraryItems.value
        
        val lowercaseQuery = query.lowercase()
        return _libraryItems.value.filter {
            it.title.lowercase().contains(lowercaseQuery) ||
            it.currentChapter.lowercase().contains(lowercaseQuery)
        }
    }
    
    /**
     * Filter items by content type
     */
    fun filterByContentType(contentType: ContentType): List<LibraryItem> {
        return _libraryItems.value.filter { it.contentType == contentType }
    }
    
    // Selection mode methods
    
    /**
     * Toggle item selection
     */
    fun toggleSelection(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        if (itemId in currentSelection) {
            currentSelection.remove(itemId)
        } else {
            currentSelection.add(itemId)
        }
        _selectedItems.value = currentSelection
    }
    
    /**
     * Select item
     */
    fun selectItem(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.add(itemId)
        _selectedItems.value = currentSelection
    }
    
    /**
     * Deselect item
     */
    fun deselectItem(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.remove(itemId)
        _selectedItems.value = currentSelection
    }
    
    /**
     * Select all items
     */
    fun selectAll() {
        _selectedItems.value = _libraryItems.value.map { it.id }.toSet()
    }
    
    /**
     * Clear selection
     */
    fun clearSelection() {
        _selectedItems.value = emptySet()
    }
    
    /**
     * Check if item is selected
     */
    fun isSelected(itemId: String): Boolean {
        return itemId in _selectedItems.value
    }
    
    /**
     * Get selection count
     */
    fun getSelectionCount(): Int {
        return _selectedItems.value.size
    }
    
    /**
     * Check if in selection mode
     */
    fun isInSelectionMode(): Boolean {
        return _selectedItems.value.isNotEmpty()
    }
    
    /**
     * Get selected items
     */
    fun getSelectedItems(): List<LibraryItem> {
        return _libraryItems.value.filter { it.id in _selectedItems.value }
    }
    
    /**
     * Reload library from preferences
     */
    suspend fun reload() = withContext(Dispatchers.IO) {
        _libraryItems.value = preferencesManager.loadLibraryItems()
    }
    
    /**
     * Clear entire library
     */
    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        _libraryItems.value = emptyList()
        _selectedItems.value = emptySet()
        saveToPreferences()
    }
    
    /**
     * Save to preferences
     */
    private fun saveToPreferences() {
        preferencesManager.saveLibraryItems(_libraryItems.value)
    }
    
    /**
     * Get library statistics
     */
    fun getStatistics(): LibraryStatistics {
        val items = _libraryItems.value
        return LibraryStatistics(
            totalItems = items.size,
            webItems = items.count { it.contentType == ContentType.WEB },
            pdfItems = items.count { it.contentType == ContentType.PDF },
            htmlItems = items.count { it.contentType == ContentType.HTML },
            averageProgress = if (items.isNotEmpty()) items.map { it.progress }.average().toInt() else 0,
            totalTitles = items.map { it.title }.distinct().size
        )
    }
    
    /**
     * Data class for library statistics
     */
    data class LibraryStatistics(
        val totalItems: Int,
        val webItems: Int,
        val pdfItems: Int,
        val htmlItems: Int,
        val averageProgress: Int,
        val totalTitles: Int
    )
}
