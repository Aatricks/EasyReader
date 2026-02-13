package io.aatricks.novelscraper.data.local

import androidx.room.*
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ReadingMode
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items ORDER BY lastRead DESC")
    fun getAllItems(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM library_items WHERE isCurrentlyReading = 1 LIMIT 1")
    suspend fun getCurrentlyReading(): LibraryItem?

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getItemById(id: String): LibraryItem?

    @Query("SELECT * FROM library_items WHERE url = :url")
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

    @Query("UPDATE library_items SET readingMode = :readingMode WHERE baseTitle = :baseTitle")
    suspend fun updateReadingModeByBaseTitle(baseTitle: String, readingMode: ReadingMode)

    @Transaction
    suspend fun setCurrentReading(id: String, timestamp: Long = System.currentTimeMillis()) {
        clearCurrentlyReading()
        markAsCurrentlyReading(id, timestamp)
    }
}
