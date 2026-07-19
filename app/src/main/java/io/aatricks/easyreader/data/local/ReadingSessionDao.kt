package io.aatricks.easyreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

data class SessionTotals(
    val totalActiveMillis: Long,
    val totalChaptersCompleted: Int,
    val sessionCount: Int
)

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt ASC")
    suspend fun getAllSessions(): List<ReadingSessionEntity>

    @Query(
        "SELECT " +
        "COALESCE(SUM(activeMillis), 0) AS totalActiveMillis, " +
        "COALESCE(SUM(chaptersCompleted), 0) AS totalChaptersCompleted, " +
        "COUNT(*) AS sessionCount " +
        "FROM reading_sessions"
    )
    fun observeTotals(): Flow<SessionTotals>

    @Query("SELECT COALESCE(COUNT(DISTINCT (startedAt / 86400000)), 0) FROM reading_sessions")
    suspend fun getDistinctReadingDayCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM reading_sessions LIMIT 1)")
    suspend fun hasAnySessions(): Boolean

    @Query(
        "UPDATE reading_sessions SET endedAt = :endedAt, activeMillis = :activeMillis, " +
        "chaptersCompleted = :chaptersCompleted WHERE id = :id"
    )
    suspend fun updateSessionProgress(id: Long, endedAt: Long, activeMillis: Long, chaptersCompleted: Int)
}
