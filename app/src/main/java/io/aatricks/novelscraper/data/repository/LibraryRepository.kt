package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.util.TextUtils
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Repository for library management with persistence
 */
class LibraryRepository(private val preferencesManager: PreferencesManager) {
    
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _libraryItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val libraryItems: StateFlow<List<LibraryItem>> = _libraryItems.asStateFlow()
    
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    private val _collapsedSources = MutableStateFlow<Set<String>>(emptySet())
    val collapsedSources: StateFlow<Set<String>> = _collapsedSources.asStateFlow()
    
    init {
        // Load library on initialization
        _libraryItems.value = preferencesManager.loadLibraryItems()
        _collapsedSources.value = preferencesManager.loadCollapsedSources()
    }
    
    /**
     * Add a new item to the library
     */
    suspend fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1",
        baseTitle: String = title, // Default to full title if not provided
        baseNovelUrl: String = "",
        sourceName: String = "",
        totalChapters: Int = 0
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
            baseTitle = baseTitle,
            baseNovelUrl = baseNovelUrl,
            sourceName = sourceName,
            totalChapters = totalChapters
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
     * Update reading mode for an item and all items with the same baseTitle
     */
    suspend fun updateReadingMode(itemId: String, readingMode: io.aatricks.novelscraper.data.model.ReadingMode): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == itemId }
        
        if (index != -1) {
            val baseTitle = currentItems[index].baseTitle
            // Update all items sharing the same baseTitle
            for (i in currentItems.indices) {
                if (currentItems[i].baseTitle == baseTitle) {
                    currentItems[i] = currentItems[i].copy(readingMode = readingMode)
                }
            }
            _libraryItems.value = currentItems
            saveToPreferences()
            true
        } else {
            false
        }
    }

    /**
     * Update baseNovelUrl and sourceName for an item and its group
     */
    suspend fun updateNovelInfo(itemId: String, baseNovelUrl: String, sourceName: String): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == itemId }
        
        if (index != -1) {
            val baseTitle = currentItems[index].baseTitle
            for (i in currentItems.indices) {
                if (currentItems[i].baseTitle == baseTitle) {
                    currentItems[i] = currentItems[i].copy(
                        baseNovelUrl = baseNovelUrl,
                        sourceName = sourceName
                    )
                }
            }
            _libraryItems.value = currentItems
            saveToPreferences()
            true
        } else {
            false
        }
    }

    /**
     * Update reading progress (non-suspending version that uses repositoryScope)
     */
    fun saveProgress(
        itemId: String,
        currentChapter: String,
        progress: Int,
        currentChapterUrl: String? = null,
        lastScrollProgress: Float? = null,
        lastReadIndex: Int? = null,
        lastReadOffset: Int? = null
    ) {
        repositoryScope.launch {
            updateProgress(
                itemId,
                currentChapter,
                progress,
                currentChapterUrl,
                lastScrollProgress,
                lastReadIndex,
                lastReadOffset
            )
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
        lastScrollProgress: Float? = null,
        lastReadIndex: Int? = null,
        lastReadOffset: Int? = null
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
                lastReadIndex = lastReadIndex ?: item.lastReadIndex,
                lastReadOffset = lastReadOffset ?: item.lastReadOffset,
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
            if (item.id == itemId && (!item.isCurrentlyReading || item.hasUpdates)) {
                currentItems[i] = item.copy(
                    isCurrentlyReading = true,
                    lastRead = System.currentTimeMillis(),
                    hasUpdates = false
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
    fun getGroupedByTitle(items: List<LibraryItem>? = null): Map<String, List<LibraryItem>> {
        val targetItems = items ?: _libraryItems.value
        // Group by baseTitle and sort each group's chapters in ascending order
        return targetItems.groupBy { item ->
            item.baseTitle.ifBlank { item.title }
        }.mapValues { (_, items) ->
            sortChapters(items)
        }
    }

    /**
     * Group items by Source, then by Base Title
     * Returns: Map<SourceName, Map<NovelTitle, List<Chapters>>>
     */
    fun getGroupedBySourceAndTitle(items: List<LibraryItem>? = null): Map<String, Map<String, List<LibraryItem>>> {
        val targetItems = items ?: _libraryItems.value
        
        // Group these novels by the source
        return targetItems.groupBy { it.sourceName.ifBlank { "Local" } }
            .mapValues { (_, sourceItems) ->
                // Then group by title within the source
                sourceItems.groupBy { it.baseTitle.ifBlank { it.title } }
                    .mapValues { (_, novelItems) -> 
                        sortChapters(novelItems)
                    }.toSortedMap()
            }.toSortedMap()
    }

    // Helper to sort chapters
    private fun sortChapters(items: List<LibraryItem>): List<LibraryItem> {
        return items.sortedWith { a, b ->
            val aNum = parseChapterNumberOrNull(a)
            val bNum = parseChapterNumberOrNull(b)
            when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                else -> a.dateAdded.compareTo(b.dateAdded)
            }
        }
    }

    private fun parseChapterNumberOrNull(item: LibraryItem): Int? {
        val cc = item.currentChapter
        if (cc.isNotBlank()) {
            val num = TextUtils.extractChapterNumber(cc)
            if (num != null) return num
        }
        val titleNum = TextUtils.extractChapterNumber(item.title)
        if (titleNum != null) return titleNum
        
        val urlNum = TextUtils.extractChapterNumber(item.url)
        if (urlNum != null) return urlNum
        return null
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
     * Search items by title
     */
    fun searchItems(query: String): List<LibraryItem> {
        if (query.isBlank()) return _libraryItems.value
        
        val lowercaseQuery = query.trim().lowercase()
        return _libraryItems.value.filter {
            it.title.lowercase().contains(lowercaseQuery) ||
            it.baseTitle.lowercase().contains(lowercaseQuery)
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
     * Select multiple items
     */
    fun selectItems(itemIds: List<String>) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.addAll(itemIds)
        _selectedItems.value = currentSelection
    }

    /**
     * Deselect multiple items
     */
    fun deselectItems(itemIds: List<String>) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.removeAll(itemIds)
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
     * Check for updates for all novels in the library.
     */
    suspend fun refreshLibraryUpdates(exploreRepository: ExploreRepository) = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val groupedItems = getGroupedByTitle()
        var updated = false

        for ((baseTitle, items) in groupedItems) {
            if (items.isEmpty()) continue
            
            // items is sorted ascending by chapter number, so last() is the latest chapter in library
            val latestInLibrary = items.last()
            if (latestInLibrary.baseNovelUrl.isBlank() || latestInLibrary.sourceName.isBlank()) continue

            try {
                val details = exploreRepository.getNovelDetails(latestInLibrary.baseNovelUrl, latestInLibrary.sourceName)
                if (details != null && details.chapters.isNotEmpty()) {
                    val sourceChapterCount = details.chapters.size
                    
                    // Only trigger update if we found more chapters than previously known
                    if (sourceChapterCount > latestInLibrary.totalChapters) {
                        val itemToMark = items.find { it.isCurrentlyReading } ?: latestInLibrary
                        
                        // Update all chapters in this group with the new total count
                        for (i in currentItems.indices) {
                            val item = currentItems[i]
                            val itemGroupKey = item.baseTitle.ifBlank { item.title }
                            
                            if (itemGroupKey == baseTitle) {
                                var newItem = item.copy(totalChapters = sourceChapterCount)
                                // Only set hasUpdates on the specific item we want to badge
                                if (item.id == itemToMark.id && !item.hasUpdates) {
                                    newItem = newItem.copy(hasUpdates = true)
                                }
                                
                                if (newItem != item) {
                                    currentItems[i] = newItem
                                    updated = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryRepository", "Failed to refresh updates for $baseTitle", e)
            }
        }

        if (updated) {
            _libraryItems.value = currentItems
            saveToPreferences()
        }
    }

    /**
     * Clear update indicator for an item
     */
    suspend fun clearUpdateIndicator(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val currentItems = _libraryItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == itemId }
        
        if (index != -1 && currentItems[index].hasUpdates) {
            currentItems[index] = currentItems[index].copy(hasUpdates = false)
            _libraryItems.value = currentItems
            saveToPreferences()
            true
        } else {
            false
        }
    }

    /**
     * Toggle source expansion state
     */
    fun toggleSourceExpansion(sourceName: String) {
        val current = _collapsedSources.value.toMutableSet()
        if (current.contains(sourceName)) {
            current.remove(sourceName)
        } else {
            current.add(sourceName)
        }
        _collapsedSources.value = current
        preferencesManager.saveCollapsedSources(current)
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
