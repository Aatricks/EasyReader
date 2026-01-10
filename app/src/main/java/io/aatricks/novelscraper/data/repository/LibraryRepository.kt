package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.util.TextUtils
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.ReadingMode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Repository for library management with Room persistence and migration from SharedPreferences
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val preferencesManager: PreferencesManager
) {
    
    private val TAG = "LibraryRepository"
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val libraryItems: StateFlow<List<LibraryItem>> = libraryDao.getAllItems()
        .catch { e -> 
            Log.e(TAG, "Error collecting library items", e)
            emit(emptyList()) 
        }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    private val _collapsedSources = MutableStateFlow<Set<String>>(emptySet())
    val collapsedSources: StateFlow<Set<String>> = _collapsedSources.asStateFlow()
    
    init {
        repositoryScope.launch {
            try {
                migrateIfNecessary()
                _collapsedSources.value = preferencesManager.loadCollapsedSources()
            } catch (e: Exception) {
                Log.e(TAG, "Error in init", e)
            }
        }
    }

    private suspend fun migrateIfNecessary() = withContext(Dispatchers.IO) {
        try {
            val legacyItems = preferencesManager.loadLibraryItems()
            if (legacyItems.isNotEmpty()) {
                val currentRoomItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
                if (currentRoomItems.isEmpty()) {
                    libraryDao.insertItems(legacyItems)
                    Log.d(TAG, "Migrated ${legacyItems.size} items from SharedPreferences")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }
    
    suspend fun addItem(
        title: String,
        url: String,
        contentType: ContentType,
        currentChapter: String = "Chapter 1",
        baseTitle: String = title,
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
        
        libraryDao.insertItem(newItem)
        newItem
    }
    
    suspend fun removeItem(itemId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = libraryDao.getItemById(itemId)
            if (item != null) {
                libraryDao.deleteItem(item)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove item", e)
            false
        }
    }
    
    suspend fun removeItems(itemIds: Set<String>): Int = withContext(Dispatchers.IO) {
        try {
            libraryDao.deleteItemsByIds(itemIds)
            itemIds.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove items", e)
            0
        }
    }
    
    suspend fun updateItem(updatedItem: LibraryItem): Boolean = withContext(Dispatchers.IO) {
        try {
            libraryDao.insertItem(updatedItem)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update item", e)
            false
        }
    }
    
    suspend fun updateReadingMode(itemId: String, readingMode: ReadingMode): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = libraryDao.getItemById(itemId)
            if (item != null) {
                val baseTitle = item.baseTitle
                val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
                val itemsToUpdate = allItems.filter { it.baseTitle == baseTitle }
                val updatedItems = itemsToUpdate.map { it.copy(readingMode = readingMode) }
                libraryDao.insertItems(updatedItems)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update reading mode", e)
            false
        }
    }

    suspend fun updateNovelInfo(itemId: String, baseNovelUrl: String, sourceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = libraryDao.getItemById(itemId)
            if (item != null) {
                val baseTitle = item.baseTitle
                val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
                val itemsToUpdate = allItems.filter { it.baseTitle == baseTitle }
                val updatedItems = itemsToUpdate.map { 
                    it.copy(baseNovelUrl = baseNovelUrl, sourceName = sourceName) 
                }
                libraryDao.insertItems(updatedItems)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update novel info", e)
            false
        }
    }

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

    suspend fun updateProgress(
        itemId: String,
        currentChapter: String,
        progress: Int,
        currentChapterUrl: String? = null,
        lastScrollProgress: Float? = null,
        lastReadIndex: Int? = null,
        lastReadOffset: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = libraryDao.getItemById(itemId)
            if (item != null) {
                val updated = item.copy(
                    currentChapter = if (currentChapter.isNotBlank()) currentChapter else item.currentChapter,
                    progress = progress,
                    currentChapterUrl = currentChapterUrl ?: item.currentChapterUrl,
                    lastScrollPosition = lastScrollProgress ?: item.lastScrollPosition,
                    lastReadIndex = lastReadIndex ?: item.lastReadIndex,
                    lastReadOffset = lastReadOffset ?: item.lastReadOffset,
                    lastRead = System.currentTimeMillis()
                )
                libraryDao.insertItem(updated)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update progress", e)
            false
        }
    }
    
    suspend fun markAsCurrentlyReading(itemId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            libraryDao.setCurrentReading(itemId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark as reading", e)
            false
        }
    }
    
    suspend fun getCurrentlyReading(): LibraryItem? {
        return try {
            libraryDao.getCurrentlyReading()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get currently reading", e)
            null
        }
    }
    
    suspend fun getItemById(itemId: String): LibraryItem? {
        return try {
            libraryDao.getItemById(itemId)
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun getItemByUrl(url: String): LibraryItem? {
        return try {
            libraryDao.getItemByUrl(url)
        } catch (e: Exception) {
            null
        }
    }
    
    fun getGroupedByTitle(items: List<LibraryItem>? = null): Map<String, List<LibraryItem>> {
        val targetItems = items ?: libraryItems.value
        return targetItems.groupBy { item ->
            item.baseTitle.ifBlank { item.title }
        }.mapValues { (_, items) ->
            sortChapters(items)
        }
    }

    fun getGroupedBySourceAndTitle(items: List<LibraryItem>? = null): Map<String, Map<String, List<LibraryItem>>> {
        val targetItems = items ?: libraryItems.value
        return targetItems.groupBy { it.sourceName.ifBlank { "Local" } }
            .mapValues { (_, sourceItems) ->
                sourceItems.groupBy { it.baseTitle.ifBlank { it.title } }
                    .mapValues { (_, novelItems) -> 
                        sortChapters(novelItems)
                    }.toSortedMap()
            }.toSortedMap()
    }

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

    fun searchItems(query: String): List<LibraryItem> {
        val allItems = libraryItems.value
        if (query.isBlank()) return allItems
        
        val lowercaseQuery = query.trim().lowercase()
        return allItems.filter {
            it.title.lowercase().contains(lowercaseQuery) ||
            it.baseTitle.lowercase().contains(lowercaseQuery)
        }
    }
    
    fun toggleSelection(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        if (itemId in currentSelection) {
            currentSelection.remove(itemId)
        } else {
            currentSelection.add(itemId)
        }
        _selectedItems.value = currentSelection
    }
    
    fun selectItem(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.add(itemId)
        _selectedItems.value = currentSelection
    }
    
    fun deselectItem(itemId: String) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.remove(itemId)
        _selectedItems.value = currentSelection
    }

    fun selectItems(itemIds: List<String>) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.addAll(itemIds)
        _selectedItems.value = currentSelection
    }

    fun deselectItems(itemIds: List<String>) {
        val currentSelection = _selectedItems.value.toMutableSet()
        currentSelection.removeAll(itemIds)
        _selectedItems.value = currentSelection
    }
    
    fun selectAll() {
        _selectedItems.value = libraryItems.value.map { it.id }.toSet()
    }
    
    fun clearSelection() {
        _selectedItems.value = emptySet()
    }

    fun getSelectedItems(): List<LibraryItem> {
        val selectedIds = _selectedItems.value
        return libraryItems.value.filter { it.id in selectedIds }
    }
    
    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        try {
            _selectedItems.value = emptySet()
            val all = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            all.forEach { libraryDao.deleteItem(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear library", e)
        }
    }

    suspend fun refreshLibraryUpdates(exploreRepository: ExploreRepository) = withContext(Dispatchers.IO) {
        try {
            val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            val groupedItems = getGroupedByTitle(allItems)

            for ((baseTitle, items) in groupedItems) {
                if (items.isEmpty()) continue
                val latestInLibrary = items.last()
                if (latestInLibrary.baseNovelUrl.isBlank() || latestInLibrary.sourceName.isBlank()) continue

                try {
                    val details = exploreRepository.getNovelDetails(latestInLibrary.baseNovelUrl, latestInLibrary.sourceName)
                    if (details != null && details.chapters.isNotEmpty()) {
                        val sourceChapterCount = details.chapters.size
                        if (sourceChapterCount > latestInLibrary.totalChapters) {
                            val itemToMark = items.find { it.isCurrentlyReading } ?: latestInLibrary
                            val updatedItems = items.map { item ->
                                var newItem = item.copy(totalChapters = sourceChapterCount)
                                if (item.id == itemToMark.id && !item.hasUpdates) {
                                    newItem = newItem.copy(hasUpdates = true)
                                }
                                newItem
                            }
                            libraryDao.insertItems(updatedItems)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh updates for $baseTitle", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh updates failed", e)
        }
    }

    suspend fun clearUpdateIndicator(itemId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = libraryDao.getItemById(itemId)
            if (item != null && item.hasUpdates) {
                libraryDao.insertItem(item.copy(hasUpdates = false))
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear update indicator", e)
            false
        }
    }

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
}
