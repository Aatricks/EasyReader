package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.inferBaseNovelUrlFromUrl
import io.aatricks.easyreader.util.inferSourceNameFromUrl
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.util.rethrowCancellation
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.LibraryBatchUpdate
import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.hasFinishedProgress
import io.aatricks.easyreader.data.model.resolvedChapterNumber
import io.aatricks.easyreader.data.model.seriesReadingStatus
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.util.FieldUpdate
import io.aatricks.easyreader.util.resolve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    // Intentionally process-lifetime: this repository is a @Singleton and owns a
    // StateFlow shared across the entire app via stateIn. Cancelling the scope at
    // any earlier boundary (e.g., ViewModel destruction) would tear down the
    // library StateFlow that other ViewModels still observe. SupervisorJob keeps
    // a single failed background task from cancelling its siblings.
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
        private const val REFRESH_PER_SOURCE_TIMEOUT_MS = 10_000L
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
            .rethrowCancellation()
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
        totalChapters: Int = 0,
        coverImageUrl: String = ""
    ): LibraryItem = io {
        val newItem = LibraryItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = url,
            currentChapter = currentChapter,
            contentType = contentType,
            dateAdded = System.currentTimeMillis(),
            lastRead = 0L,
            isCurrentlyReading = false,
            baseTitle = baseTitle,
            baseNovelUrl = baseNovelUrl,
            sourceName = sourceName,
            totalChapters = totalChapters,
            coverImageUrl = coverImageUrl
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

    /**
     * Update only chapter-metadata columns via targeted UPDATEs, never touching progress
     * columns. Used by the reader's chapter-list heal, which previously did a whole-row
     * `updateItem` (REPLACE) outside `progressMutex` — a lost-update window that could revert a
     * concurrent progress write. Targeted column writes make that read→write gap harmless, so
     * no mutex is needed (same approach as the background-refresh path). Pass `null` to leave a
     * field unchanged.
     */
    suspend fun healChapterMetadata(
        itemId: String,
        totalChapters: Int?,
        currentChapter: String?,
        markHasUpdates: Boolean
    ): Boolean = runRepoCatching("Failed to heal chapter metadata", false) {
        totalChapters?.let { libraryDao.updateTotalChapters(itemId, it) }
        currentChapter?.let { libraryDao.updateCurrentChapter(itemId, it) }
        if (markHasUpdates) libraryDao.markHasUpdates(itemId)
        true
    } ?: false

    suspend fun healNovelMetadata(
        itemId: String,
        baseTitle: String? = null,
        baseNovelUrl: String? = null,
        sourceName: String? = null
    ): Boolean = runRepoCatching("Failed to heal novel metadata", false) {
        val item = libraryDao.getItemById(itemId) ?: return@runRepoCatching false
        val newBaseTitle = baseTitle?.ifBlank { null } ?: item.baseTitle
        val newBaseNovelUrl = baseNovelUrl?.ifBlank { null } ?: item.baseNovelUrl
        val newSourceName = sourceName?.ifBlank { null } ?: item.sourceName
        libraryDao.updateNovelMetadata(itemId, newBaseTitle, newBaseNovelUrl, newSourceName)
        true
    } ?: false

    suspend fun updateReadingMode(itemId: String, readingMode: ReadingMode): Boolean =
        runRepoCatching("Failed to update reading mode", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                libraryDao.updateReadingModeByBaseTitle(item.baseTitle, readingMode)
                true
            } ?: false
        } ?: false

    suspend fun updateCoverImageUrl(displayTitle: String, sourceName: String, cover: String): Boolean =
        runRepoCatching("Failed to update cover image URL", false) {
            libraryDao.updateCoverImageUrl(displayTitle, sourceName, cover) > 0
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
        lastReadElementKey: String? = null,
        lastReadOffsetFraction: Float? = null
    ): Unit {
        saveProgressExplicitAsync(
            itemId = itemId,
            currentChapter = currentChapter,
            progress = FieldUpdate.Set(progress),
            currentChapterUrl = currentChapterUrl?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastScrollProgress = lastScrollProgress?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastReadIndex = lastReadIndex?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
            lastReadElementKey = lastReadElementKey?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
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
        lastReadElementKey: FieldUpdate<String> = FieldUpdate.Unchanged,
        lastReadOffsetFraction: FieldUpdate<Float> = FieldUpdate.Unchanged
    ): Unit {
        repositoryScope.launch {
            updateProgressExplicit(
                itemId,
                currentChapter,
                progress,
                currentChapterUrl,
                lastScrollProgress,
                lastReadIndex,
                lastReadElementKey,
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
        lastReadElementKey: String? = null,
        lastReadOffsetFraction: Float? = null
    ): Boolean = updateProgressExplicit(
        itemId = itemId,
        currentChapter = currentChapter,
        progress = FieldUpdate.Set(progress),
        currentChapterUrl = currentChapterUrl?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastScrollProgress = lastScrollProgress?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadIndex = lastReadIndex?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadElementKey = lastReadElementKey?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged,
        lastReadOffsetFraction = lastReadOffsetFraction?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Unchanged
    )

    suspend fun updateProgressExplicit(
        itemId: String,
        currentChapter: String = "",
        progress: FieldUpdate<Int> = FieldUpdate.Unchanged,
        currentChapterUrl: FieldUpdate<String> = FieldUpdate.Unchanged,
        lastScrollProgress: FieldUpdate<Float> = FieldUpdate.Unchanged,
        lastReadIndex: FieldUpdate<Int> = FieldUpdate.Unchanged,
        lastReadElementKey: FieldUpdate<String> = FieldUpdate.Unchanged,
        lastReadOffsetFraction: FieldUpdate<Float> = FieldUpdate.Unchanged
    ): Boolean = progressMutex.withLock {
        runRepoCatching("Failed to update progress", false) {
            libraryDao.getItemById(itemId)?.let { item ->
                val updated = item.copy(
                    currentChapter = currentChapter.ifBlank { item.currentChapter },
                    progress = progress.resolve(item.progress, 0),
                    currentChapterUrl = currentChapterUrl.resolve(item.currentChapterUrl, ""),
                    lastScrollPosition = lastScrollProgress.resolve(item.lastScrollPosition, 0f),
                    lastReadIndex = lastReadIndex.resolve(item.lastReadIndex, 0),
                    lastReadElementKey = lastReadElementKey.resolve(item.lastReadElementKey, ""),
                    lastReadOffsetFraction = lastReadOffsetFraction.resolve(
                        item.lastReadOffsetFraction,
                        io.aatricks.easyreader.data.model.FRACTION_UNKNOWN
                    ),
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
            it.libraryDisplayTitle() == baseTitle ||
                it.baseTitle.equals(baseTitle, ignoreCase = true) ||
                it.title.equals(baseTitle, ignoreCase = true)
        }
        return sortChapters(filtered)
    }

    fun getGroupedByTitle(items: List<LibraryItem>? = null): Map<String, List<LibraryItem>> {
        val targetItems = items ?: libraryItems.value
        return targetItems.groupBy { item ->
            item.libraryDisplayTitle()
        }.mapValues { (_, group) ->
            sortChapters(group)
        }
    }

    fun getGroupedBySourceAndTitle(items: List<LibraryItem>? = null): Map<String, Map<String, List<LibraryItem>>> {
        val targetItems = items ?: libraryItems.value
        return targetItems.groupBy { it.sourceName.ifBlank { "Local" } }
            .mapValues { (_, sourceItems) ->
                sourceItems.groupBy { it.libraryDisplayTitle() }
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

    suspend fun refreshLibraryUpdates(
        exploreRepository: ExploreRepository,
        ignoreActivityThreshold: Boolean = false
    ): Unit = io {
        runRepoCatching("Refresh updates failed") {
            val allItems = libraryDao.getAllItems().firstOrNull() ?: emptyList()
            val groupedItems = allItems.groupBy { item ->
                Pair(item.libraryDisplayTitle(), item.sourceName)
            }.mapValues { (_, group) ->
                sortChapters(group)
            }
            val semaphore = Semaphore(5)

            // Only check for updates on novels that have been read recently or were added recently.
            // This prevents checking hundreds of abandoned novels on every app launch.
            // A user-initiated run (e.g. right after a library import) passes
            // ignoreActivityThreshold = true so freshly restored "finished" series — whose
            // lastRead/dateAdded are old, copied from the backup — still get checked and can
            // surface their NEW pills.
            val threshold = System.currentTimeMillis() - UPDATE_CHECK_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
            val activeGroups = groupedItems.filter { (_, items) ->
                items.isNotEmpty() && (
                    ignoreActivityThreshold ||
                        seriesReadingStatus(items) == SeriesReadingStatus.FINISHED ||
                        items.any {
                            it.isCurrentlyReading || it.lastRead > threshold || it.dateAdded > threshold
                        }
                    )
            }

            val channel = Channel<Pair<Pair<String, String>, List<LibraryItem>>>(Channel.UNLIMITED)
            activeGroups.forEach { channel.trySend(it.key to it.value) }
            channel.close()

            val allUpdates = coroutineScope {
                val workers = (1..5).map {
                    async {
                        val localUpdates = mutableListOf<LibraryBatchUpdate>()
                        for ((key, items) in channel) {
                            val (baseTitle, _) = key
                            if (items.isNotEmpty()) {
                                val latestInLibrary = items.last()
                                val rawSource = latestInLibrary.sourceName
                                val sourceName = rawSource.ifBlank {
                                    inferSourceNameFromUrl(latestInLibrary.url)
                                }
                                val rawBaseNovelUrl = latestInLibrary.baseNovelUrl
                                val baseNovelUrl = rawBaseNovelUrl.ifBlank {
                                    inferBaseNovelUrlFromUrl(latestInLibrary.url)
                                }
                                if (baseNovelUrl.isNotBlank() && sourceName.isNotBlank()) {
                                    if (rawSource.isBlank() || rawBaseNovelUrl.isBlank() || latestInLibrary.baseTitle.isBlank()) {
                                        healNovelMetadata(
                                            itemId = latestInLibrary.id,
                                            baseTitle = latestInLibrary.baseTitle.ifBlank { baseTitle },
                                            baseNovelUrl = baseNovelUrl,
                                            sourceName = sourceName
                                        )
                                    }
                                    val newUpdates = runRepoCatching("Failed to refresh updates for $baseTitle", emptyList<LibraryBatchUpdate>()) {
                                        val details = withTimeoutOrNull(REFRESH_PER_SOURCE_TIMEOUT_MS) {
                                            exploreRepository.getNovelDetails(
                                                baseNovelUrl,
                                                sourceName
                                            )
                                        }
                                        if (details != null && details.chapters.isNotEmpty()) {
                                            val sourceChapterCount = normalizeChapterList(details.chapters).size
                                            val previousTotalChapters = latestInLibrary.totalChapters
                                            if (sourceChapterCount > previousTotalChapters) {
                                                val itemToMark = items.find { it.isCurrentlyReading } ?: latestInLibrary
                                                val markerChapterNumber = itemToMark.resolvedChapterNumber()
                                                val wasCaughtUp = previousTotalChapters > 0 &&
                                                    markerChapterNumber != null &&
                                                    markerChapterNumber >= previousTotalChapters.toDouble() &&
                                                    itemToMark.hasFinishedProgress()
                                                items.map { item ->
                                                    val markUpdate = wasCaughtUp &&
                                                        item.id == itemToMark.id &&
                                                        !item.hasUpdates
                                                    LibraryBatchUpdate(
                                                        itemId = item.id,
                                                        newTotalChapters = sourceChapterCount,
                                                        markHasUpdates = markUpdate
                                                    )
                                                }
                                            } else {
                                                emptyList()
                                            }
                                        } else {
                                            emptyList()
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
                libraryDao.applyLibraryUpdates(allUpdates)
            }
        }
    }

    suspend fun markDownloaded(itemId: String, downloaded: Boolean): Boolean = runRepoCatching("Failed to update download flag", false) {
        val ts = if (downloaded) System.currentTimeMillis() else null
        libraryDao.setDownloaded(itemId, downloaded, ts)
        true
    } ?: false

    suspend fun getDownloadedItems(): List<LibraryItem> = runRepoCatching("Failed to fetch downloaded items", emptyList<LibraryItem>()) {
        libraryDao.getDownloadedItems()
    } ?: emptyList()

    suspend fun getAllItemsSnapshot(): List<LibraryItem> = runRepoCatching("Failed to fetch all items", emptyList<LibraryItem>()) {
        libraryDao.getAllItems().first()
    } ?: emptyList()

    suspend fun restoreItems(items: List<LibraryItem>): Int = io {
        if (items.isEmpty()) return@io 0
        libraryDao.insertItems(items)
        items.size
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
