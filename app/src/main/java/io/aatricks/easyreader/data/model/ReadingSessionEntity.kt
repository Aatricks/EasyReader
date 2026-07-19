package io.aatricks.easyreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    indices = [
        Index(value = ["novelKey"])
    ]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val novelKey: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeMillis: Long,
    val chaptersCompleted: Int,
    val seeded: Boolean = false
)
