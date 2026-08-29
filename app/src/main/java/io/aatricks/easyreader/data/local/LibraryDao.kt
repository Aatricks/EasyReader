package io.aatricks.easyreader.data.local

import androidx.room.*
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingMode
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items ORDER BY lastRead DESC")
    fun getAllItems(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM library_items")
    suspend fun getAllItemsDirect(): List<LibraryItem>

    @Query("SELECT * FROM library_items WHERE isCurrentlyReading = 1 LIMIT 1")
    suspend fun getCurrentlyReading(): LibraryItem?

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getItemById(id: String): LibraryItem?

    @Query("SELECT * FROM library_items WHERE url = :url ORDER BY lastRead DESC LIMIT 1")
    suspend fun getItemByUrl(url: String): LibraryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: LibraryItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<LibraryItem>)

    @Delete
    suspend fun deleteItem(item: LibraryItem)

    @Query("DELETE FROM library_items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: Set<String>)

    @Query("UPDATE library_items SET isCurrentlyReading = 0")
    suspend fun clearCurrentlyReading()

    @Query("UPDATE library_items SET isCurrentlyReading = 1, lastRead = :timestamp WHERE id = :id")
    suspend fun markAsCurrentlyReading(id: String, timestamp: Long)

    @Query("UPDATE library_items SET hasUpdates = 0 WHERE baseTitle = :baseTitle")
    suspend fun clearUpdatesForBaseTitle(baseTitle: String)

    @Query("UPDATE library_items SET hasUpdates = 0 WHERE id = :id")
    suspend fun clearUpdatesForId(id: String)

    @Query("UPDATE library_items SET baseNovelUrl = :baseNovelUrl, sourceName = :sourceName WHERE baseTitle = (SELECT baseTitle FROM library_items WHERE id = :itemId)")
    suspend fun updateNovelInfo(itemId: String, baseNovelUrl: String, sourceName: String): Int

    @Query(
        "UPDATE library_items SET baseTitle = :baseTitle, " +
        "baseNovelUrl = :baseNovelUrl, sourceName = :sourceName WHERE id = :id"
    )
    suspend fun updateNovelMetadata(id: String, baseTitle: String, baseNovelUrl: String, sourceName: String): Int

    @Query("UPDATE library_items SET readingMode = :readingMode WHERE baseTitle = :baseTitle")
    suspend fun updateReadingModeByBaseTitle(baseTitle: String, readingMode: ReadingMode)

    @Query(
        "UPDATE library_items SET coverImageUrl = :cover " +
        "WHERE (baseTitle = :displayTitle OR (baseTitle = '' AND title = :displayTitle)) " +
        "AND sourceName = :sourceName"
    )
    suspend fun updateCoverImageUrl(displayTitle: String, sourceName: String, cover: String): Int

    @Query("UPDATE library_items SET isDownloaded = :downloaded, downloadedAt = :timestamp WHERE id = :id")
    suspend fun setDownloaded(id: String, downloaded: Boolean, timestamp: Long?)

    @Query("UPDATE library_items SET totalChapters = :totalChapters WHERE id = :id")
    suspend fun updateTotalChapters(id: String, totalChapters: Int)

    @Query("UPDATE library_items SET hasUpdates = 1 WHERE id = :id")
    suspend fun markHasUpdates(id: String)

    @Query("UPDATE library_items SET currentChapter = :currentChapter WHERE id = :id")
    suspend fun updateCurrentChapter(id: String, currentChapter: String)

    @Query("SELECT * FROM library_items WHERE isDownloaded = 1")
    suspend fun getDownloadedItems(): List<LibraryItem>

    @Query("DELETE FROM library_items")
    suspend fun deleteAllItems()

    @Query("UPDATE library_items SET progress = 0, lastScrollPosition = 0, lastReadIndex = 0, lastReadElementKey = '', lastReadOffsetFraction = -1, lastRead = :timestamp WHERE id = :id")
    suspend fun resetProgress(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET progress = 0, lastScrollPosition = 0, lastReadIndex = 0, lastReadElementKey = '', lastReadOffsetFraction = -1 WHERE baseTitle = :baseTitle")
    suspend fun resetProgressByBaseTitle(baseTitle: String)

    @Transaction
    suspend fun applyLibraryUpdates(updates: List<LibraryBatchUpdate>) {
        updates.forEach { update ->
            updateTotalChapters(update.itemId, update.newTotalChapters)
            if (update.markHasUpdates) {
                markHasUpdates(update.itemId)
            }
        }
    }

    @Transaction
    suspend fun setCurrentReading(id: String, timestamp: Long = System.currentTimeMillis()) {
        clearCurrentlyReading()
        markAsCurrentlyReading(id, timestamp)
    }
}

data class LibraryBatchUpdate(
    val itemId: String,
    val newTotalChapters: Int,
    val markHasUpdates: Boolean
)
