package io.aatricks.easyreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aatricks.easyreader.data.model.ChapterImageStateEntity

@Dao
interface ChapterImageStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChapterImageStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterImageStateEntity>)

    @Query("""
        SELECT imageUrl FROM chapter_image_state
        WHERE chapterUrl = :chapterUrl
          AND status = :status
          AND lastAttemptMs > :freshAfterMs
    """)
    suspend fun getActiveImagesByStatus(
        chapterUrl: String,
        status: String,
        freshAfterMs: Long
    ): List<String>

    @Query("""
        SELECT * FROM chapter_image_state
        WHERE chapterUrl = :chapterUrl
          AND status = :status
    """)
    suspend fun getAllByStatus(
        chapterUrl: String,
        status: String
    ): List<ChapterImageStateEntity>

    @Query("DELETE FROM chapter_image_state WHERE chapterUrl = :chapterUrl")
    suspend fun deleteAllForChapter(chapterUrl: String)

    @Query("""
        DELETE FROM chapter_image_state
        WHERE chapterUrl = :chapterUrl AND status = :status
    """)
    suspend fun deleteForChapterByStatus(chapterUrl: String, status: String)

    @Query("""
        DELETE FROM chapter_image_state
        WHERE status = :status AND lastAttemptMs < :olderThanMs
    """)
    suspend fun pruneExpired(status: String, olderThanMs: Long)
}
