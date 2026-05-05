package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.model.hasFinishedProgress
import io.aatricks.easyreader.util.FieldUpdate
import io.aatricks.easyreader.util.resolve
import io.aatricks.easyreader.util.resolveNullable

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
import kotlinx.coroutines.channels.Channel
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
) {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressMutex = Mutex()

    val libraryItems: StateFlow<List<LibraryItem>> = libraryDao.getAllItems()
        .catch { e ->
            Log.e(TAG, "Error collecting library items", e)
            emit(emptyList())
        }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5000), emptyList())

    companion object {
        private const val TAG = "LibraryRepository"
        private const val UPDATE_CHECK_THRESHOLD_DAYS = 7L
    }

    init {
        repositoryScope.launch {
            try {
                migrateIfNecessary()
            } catch (e: Exception) {
                Log.e(TAG, "Error in init", e)
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    private suspend fun <T> runRepoCatching(
        errorMessage: String,
        fallback: T? = null,
        block: suspend () -> T
    ): T? = withContext(Dispatchers.IO) {
        kotlin.runCatching { block() }
            .onFailure { e -> Log.e(TAG, errorMessage, e) }
            .getOrDefault(fallback)
    }

    private suspend fun migrateIfNecessary(): Unit = io {
        runRepoCatching("Migration failed") {
            val legacyItems = preferencesManager.loadLibraryItems()
            if (legacyItems.isNotEmpty()) {
                val currentRoomItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
                if (currentRoomItems.isEmpty()) {
                    libraryDao.insertItems(legacyItems)
                    Log.d(TAG, "Migrated ${legacyItems.size} items from SharedPreferences")
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

    suspend fun removeItem(itemId: String): Boolean = runRepoCatching("Failed to remove item", false) {
        libraryDao.getItemById(itemId)?.let { item ->
            libraryDao.deleteItem(item)
            true
        } ?: false
    } ?: false

    suspend fun removeItems(itemIds: Set<String>): Int = runRepoCatching("Failed to remove items", 0) {
        libraryDao.deleteItemsByIds(itemIds)
        itemIds.size
    } ?: 0

    suspend fun updateItem(updatedItem: LibraryItem): Boolean = runRepoCatching("Failed to update item", false) {
        libraryDao.insertItem(updatedItem)
        true
    } ?: false

    suspend fun updateReadingMode(itemId: String, readingMode: ReadingMode): Boolean =
        runRepoCatching("Failed to update reading mode", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                libraryDao.updateReadingModeByBaseTitle(item.baseTitle, readingMode)
                true
            } ?: false
        } ?: false

    suspend fun updateNovelInfo(itemId: String, baseNovelUrl: String, sourceName: String): Boolean =
        runRepoCatching("Failed to update novel info", false) {
            val updatedCount = libraryDao.updateNovelInfo(itemId, baseNovelUrl, sourceName)
            updatedCount > 0
        } ?: false

    fun saveProgressAsync(
        itemId: String,
        currentChapter: String,
        progress: Int,
        currentChapterUrl: String? = null,
        lastScrollProgress: Float? = null,
        lastReadIndex: Int? = null,
        lastReadOffset: Int? = null,
        lastReadOffsetFraction: Float? = null
    ): Unit {
        saveProgressExplicitAsync(
            itemId = itemId,
            currentChapter = currentChapter,
            progress = FieldUpdate.Set(progress),
            currentChapterUrl = currentChapterUrl?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastScrollProgress = lastScrollProgress?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastReadIndex = lastReadIndex?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastReadOffset = lastReadOffset?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastReadOffsetFraction = lastReadOffsetFraction?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged
        )
    }

    fun saveProgressExplicitAsync(
        itemId: String,
        currentChapter: String = "",
        progress: FieldUpdate<Int> = FieldUpdate.Unchanged,
        currentChapterUrl: FieldUpdate<String> = FieldUpdate.Unchanged,
        lastScrollProgress: FieldUpdate<Float> = FieldUpdate.Unchanged,
        lastReadIndex: FieldUpdate<Int> = FieldUpdate.Unchanged,
        lastReadOffset: FieldUpdate<Int> = FieldUpdate.Unchanged,
        lastReadOffsetFraction: FieldUpdate<Float?> = FieldUpdate.Unchanged
    ): Unit {
        repositoryScope.launch {
            updateProgressExplicit(
                itemId,
                currentChapter,
                progress,
                currentChapterUrl,
                lastScrollProgress,
                lastReadIndex,
                lastReadOffset,
                lastReadOffsetFraction
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
        lastReadOffset: Int? = null,
        lastReadOffsetFraction: Float? = null
    ): Boolean = updateProgressExplicit(
        itemId = itemId,
        currentChapter = currentChapter,
        progress = FieldUpdate.Set(progress),
        currentChapterUrl = currentChapterUrl?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastScrollProgress = lastScrollProgress?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadIndex = lastReadIndex?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadOffset = lastReadOffset?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadOffsetFraction = lastReadOffsetFraction?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged
    )

    suspend fun updateProgressExplicit(
        itemId: String,
        currentChapter: String = "",
        progress: FieldUpdate<Int> = FieldUpdate.Unchanged,
        currentChapterUrl: FieldUpdate<String> = FieldUpdate.Unchanged,
        lastScrollProgress: FieldUpdate<Float> = FieldUpdate.Unchanged,
        lastReadIndex: FieldUpdate<Int> = FieldUpdate.Unchanged,
        lastReadOffset: FieldUpdate<Int> = FieldUpdate.Unchanged,
        lastReadOffsetFraction: FieldUpdate<Float?> = FieldUpdate.Unchanged
    ): Boolean = progressMutex.withLock {
        runRepoCatching("Failed to update progress", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                val updated = item.copy(
                    currentChapter = currentChapter.ifBlank { item.currentChapter },
                    progress = progress.resolve(item.progress, 0),
                    currentChapterUrl = currentChapterUrl.resolve(item.currentChapterUrl, ""),
                    lastScrollPosition = lastScrollProgress.resolve(item.lastScrollPosition, 0f),
                    lastReadIndex = lastReadIndex.resolve(item.lastReadIndex, 0),
                    lastReadOffset = lastReadOffset.resolve(item.lastReadOffset, 0),
                    lastReadOffsetFraction = lastReadOffsetFraction.resolveNullable(item.lastReadOffsetFraction),
                    lastRead = System.currentTimeMillis()
                )
                libraryDao.insertItem(updated)
                true
            } ?: false
        } ?: false
    }

    suspend fun markAsCurrentlyReading(itemId: String): Boolean = runRepoCatching("Failed to mark as reading", false) {
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

    suspend fun getCurrentlyReading(): LibraryItem? = runRepoCatching("Failed to get currently reading") {
        libraryDao.getCurrentlyReading() ?: libraryDao.getAllItems().firstOrNull()?.firstOrNull()
    }

    suspend fun getItemById(itemId: String): LibraryItem? = io {
        libraryDao.getItemById(itemId)
    }

    suspend fun getItemByUrl(url: String): LibraryItem? = io {
        libraryDao.getItemByUrl(url)
    }

    fun getChaptersByBaseTitle(baseTitle: String): List<LibraryItem> {
        val allItems = libraryItems.value
        val filtered = allItems.filter {
            (it.baseTitle.ifBlank { it.title }) == baseTitle
        }
        return sortChapters(filtered)
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

    fun loadCollapsedSources(): Set<String> = preferencesManager.loadCollapsedSources()

    fun saveCollapsedSources(sources: Set<String>): Unit {
        preferencesManager.saveCollapsedSources(sources)
    }

    suspend fun clearLibrary(): Unit = io {
        runRepoCatching("Failed to clear library") {
            libraryDao.deleteAllItems()
        }
    }

    suspend fun resetProgress(itemId: String): Boolean = runRepoCatching("Failed to reset progress", false) {
        libraryDao.getItemById(itemId)?.let {
            libraryDao.resetProgress(itemId)
            true
        } ?: false
    } ?: false

    suspend fun resetProgressByBaseTitle(baseTitle: String): Boolean = runRepoCatching("Failed to reset novel progress", false) {
        libraryDao.resetProgressByBaseTitle(baseTitle)
        true
    } ?: false

    suspend fun refreshLibraryUpdates(exploreRepository: ExploreRepository): Unit = io {
        runRepoCatching("Refresh updates failed") {
            val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            val groupedItems = getGroupedByTitle(allItems)
            val semaphore = Semaphore(5)

            // Only check for updates on novels that have been read recently or were added recently.
            // This prevents checking hundreds of abandoned novels on every app launch.
            val threshold = System.currentTimeMillis() - UPDATE_CHECK_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
            val activeGroups = groupedItems.filter { (_, items) ->
                items.isNotEmpty() && items.any {
                    it.isCurrentlyReading || it.lastRead > threshold || it.dateAdded > threshold
                }
            }

            val channel = Channel<Pair<String, List<LibraryItem>>>(Channel.UNLIMITED)
            activeGroups.forEach { channel.trySend(it.key to it.value) }
            channel.close()

            val allUpdates = coroutineScope {
                val workers = (1..5).map {
                    async {
                        val localUpdates = mutableListOf<LibraryItem>()
                        for ((baseTitle, items) in channel) {
                            if (items.isNotEmpty()) {
                                val latestInLibrary = items.last()
                                if (latestInLibrary.baseNovelUrl.isNotBlank() && latestInLibrary.sourceName.isNotBlank()) {
                                    val newUpdates = runRepoCatching("Failed to refresh updates for $baseTitle", emptyList<LibraryItem>()) {
                                        val details = exploreRepository.getNovelDetails(
                                            latestInLibrary.baseNovelUrl,
                                            latestInLibrary.sourceName
                                        )
                                        if (details != null && details.chapters.isNotEmpty()) {
                                            val sourceChapterCount = normalizeChapterList(details.chapters).size
                                            val previousTotalChapters = latestInLibrary.totalChapters
                                            if (sourceChapterCount > previousTotalChapters) {
                                                val itemToMark = items.find { it.isCurrentlyReading } ?: latestInLibrary
                                                val markerChapterNumber = TextUtils.extractChapterNumber(itemToMark.currentChapter)
                                                    ?: itemToMark.currentChapterUrl.takeIf { it.isNotBlank() }?.let(TextUtils::extractChapterNumber)
                                                    ?: TextUtils.extractChapterNumber(itemToMark.url)
                                                val wasCaughtUp = previousTotalChapters > 0 &&
                                                    markerChapterNumber != null &&
                                                    markerChapterNumber >= previousTotalChapters.toDouble() &&
                                                    itemToMark.hasFinishedProgress()
                                                items.map { item ->
                                                    var newItem = item.copy(totalChapters = sourceChapterCount)
                                                    if (wasCaughtUp && item.id == itemToMark.id && !item.hasUpdates) {
                                                        newItem = newItem.copy(hasUpdates = true)
                                                    }
                                                    newItem
                                                }
                                            } else {
                                                emptyList<LibraryItem>()
                                            }
                                        } else {
                                            emptyList<LibraryItem>()
                                        }
                                    } ?: emptyList()
                                    localUpdates.addAll(newUpdates)
                                }
                            }
                        }
                        localUpdates
                    }
                }
                workers.awaitAll().flatten()
            }

            if (allUpdates.isNotEmpty()) {
                libraryDao.insertItems(allUpdates)
            }
        }
    }

    suspend fun clearUpdateIndicator(itemId: String): Boolean = runRepoCatching("Failed to clear update indicator", false) {
        libraryDao.getItemById(itemId)?.let { item ->
            if (item.hasUpdates) {
                libraryDao.insertItem(item.copy(hasUpdates = false))
                true
            } else false
        } ?: false
    } ?: false

}
