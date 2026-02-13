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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
) : BaseRepository("LibraryRepository") {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressMutex = Mutex()

    val libraryItems: StateFlow<List<LibraryItem>> = libraryDao.getAllItems()
        .catch { e ->
            Log.e(tag, "Error collecting library items", e)
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
                Log.e(tag, "Error in init", e)
            }
        }
    }

    private suspend fun migrateIfNecessary(): Unit = io {
        runCatching("Migration failed") {
            val legacyItems = preferencesManager.loadLibraryItems()
            if (legacyItems.isNotEmpty()) {
                val currentRoomItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
                if (currentRoomItems.isEmpty()) {
                    libraryDao.insertItems(legacyItems)
                    Log.d(tag, "Migrated ${legacyItems.size} items from SharedPreferences")
                }
            }
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
    ): LibraryItem = io {
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

    suspend fun removeItem(itemId: String): Boolean = runCatching("Failed to remove item", false) {
        libraryDao.getItemById(itemId)?.let { item ->
            libraryDao.deleteItem(item)
            true
        } ?: false
    } ?: false

    suspend fun removeItems(itemIds: Set<String>): Int = runCatching("Failed to remove items", 0) {
        libraryDao.deleteItemsByIds(itemIds)
        itemIds.size
    } ?: 0

    suspend fun updateItem(updatedItem: LibraryItem): Boolean = runCatching("Failed to update item", false) {
        libraryDao.insertItem(updatedItem)
        true
    } ?: false

    suspend fun updateReadingMode(itemId: String, readingMode: ReadingMode): Boolean =
        runCatching("Failed to update reading mode", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                libraryDao.updateReadingModeByBaseTitle(item.baseTitle, readingMode)
                true
            } ?: false
        } ?: false

    suspend fun updateNovelInfo(itemId: String, baseNovelUrl: String, sourceName: String): Boolean =
        runCatching("Failed to update novel info", false) {
            val updatedCount = libraryDao.updateNovelInfo(itemId, baseNovelUrl, sourceName)
            updatedCount > 0
        } ?: false

    fun saveProgress(
        itemId: String,
        currentChapter: String,
        progress: Int,
        currentChapterUrl: String? = null,
        lastScrollProgress: Float? = null,
        lastReadIndex: Int? = null,
        lastReadOffset: Int? = null
    ): Unit {
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
    ): Boolean = progressMutex.withLock {
        runCatching("Failed to update progress", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                val updated = item.copy(
                    currentChapter = currentChapter.ifBlank { item.currentChapter },
                    progress = progress,
                    currentChapterUrl = currentChapterUrl ?: item.currentChapterUrl,
                    lastScrollPosition = lastScrollProgress ?: item.lastScrollPosition,
                    lastReadIndex = lastReadIndex ?: item.lastReadIndex,
                    lastReadOffset = lastReadOffset ?: item.lastReadOffset,
                    lastRead = System.currentTimeMillis()
                )
                libraryDao.insertItem(updated)
                true
            } ?: false
        } ?: false
    }

    suspend fun markAsCurrentlyReading(itemId: String): Boolean = runCatching("Failed to mark as reading", false) {
        libraryDao.getItemById(itemId)?.let { item ->
            if (item.baseTitle.isNotBlank()) {
                libraryDao.clearUpdatesForBaseTitle(item.baseTitle)
            } else {
                libraryDao.clearUpdatesForId(itemId)
            }
        }
        libraryDao.setCurrentReading(itemId)
        true
    } ?: false

    suspend fun getCurrentlyReading(): LibraryItem? = runCatching("Failed to get currently reading") {
        libraryDao.getCurrentlyReading() ?: libraryDao.getAllItems().firstOrNull()?.firstOrNull()
    }

    suspend fun getItemById(itemId: String): LibraryItem? = io {
        libraryDao.getItemById(itemId)
    }

    suspend fun getItemByUrl(url: String): LibraryItem? = io {
        libraryDao.getItemByUrl(url)
    }

    fun getGroupedByTitle(items: List<LibraryItem>? = null): Map<String, List<LibraryItem>> {
        val targetItems = items ?: libraryItems.value
        return targetItems.groupBy { item ->
            item.baseTitle.ifBlank { item.title }
        }.mapValues { (_, group) ->
            sortChapters(group)
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

    private fun parseChapterNumberOrNull(item: LibraryItem): Double? {
        if (item.currentChapter.isNotBlank()) {
            TextUtils.extractChapterNumber(item.currentChapter)?.let { return it }
        }
        TextUtils.extractChapterNumber(item.title)?.let { return it }
        TextUtils.extractChapterNumber(item.url)?.let { return it }
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

    fun toggleSelection(itemId: String): Unit {
        val currentSelection = _selectedItems.value.toMutableSet()
        if (itemId in currentSelection) {
            currentSelection.remove(itemId)
        } else {
            currentSelection.add(itemId)
        }
        _selectedItems.value = currentSelection
    }

    fun selectItem(itemId: String): Unit {
        _selectedItems.update { it + itemId }
    }

    fun deselectItem(itemId: String): Unit {
        _selectedItems.update { it - itemId }
    }

    fun selectItems(itemIds: List<String>): Unit {
        _selectedItems.update { it + itemIds }
    }

    fun deselectItems(itemIds: List<String>): Unit {
        _selectedItems.update { it - itemIds }
    }

    fun selectAll(): Unit {
        _selectedItems.value = libraryItems.value.map { it.id }.toSet()
    }

    fun clearSelection(): Unit {
        _selectedItems.value = emptySet()
    }

    fun getSelectedItems(): List<LibraryItem> {
        val selectedIds = _selectedItems.value
        return libraryItems.value.filter { it.id in selectedIds }
    }

    suspend fun clearLibrary(): Unit = io {
        runCatching("Failed to clear library") {
            _selectedItems.value = emptySet()
            val all = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            all.forEach { libraryDao.deleteItem(it) }
        }
    }

    suspend fun refreshLibraryUpdates(exploreRepository: ExploreRepository): Unit = io {
        runCatching("Refresh updates failed") {
            val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            val groupedItems = getGroupedByTitle(allItems)
            val semaphore = Semaphore(5)

            coroutineScope {
                groupedItems.map { (baseTitle, items) ->
                    async {
                        semaphore.withPermit {
                            if (items.isNotEmpty()) {
                                val latestInLibrary = items.last()
                                if (latestInLibrary.baseNovelUrl.isNotBlank() && latestInLibrary.sourceName.isNotBlank()) {
                                    runCatching("Failed to refresh updates for $baseTitle") {
                                        val details =
                                            exploreRepository.getNovelDetails(
                                                latestInLibrary.baseNovelUrl,
                                                latestInLibrary.sourceName
                                            )
                                        if (details != null && details.chapters.isNotEmpty()) {
                                            val sourceChapterCount = details.chapters.size
                                            if (sourceChapterCount > latestInLibrary.totalChapters) {
                                                val itemToMark =
                                                    items.find { it.isCurrentlyReading } ?: latestInLibrary
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
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun clearUpdateIndicator(itemId: String): Boolean = runCatching("Failed to clear update indicator", false) {
        libraryDao.getItemById(itemId)?.let { item ->
            if (item.hasUpdates) {
                libraryDao.insertItem(item.copy(hasUpdates = false))
                true
            } else false
        } ?: false
    } ?: false

    fun toggleSourceExpansion(sourceName: String): Unit {
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
