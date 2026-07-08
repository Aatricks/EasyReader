package io.aatricks.easyreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Sentinel used in `lastReadOffsetFraction` to mean "intra-item position not yet measured".
 * Keeps the column NOT NULL while preserving the "unknown" branch in restore logic.
 */
const val FRACTION_UNKNOWN: Float = -1f

@Serializable
@Entity(
    tableName = "library_items",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["baseTitle"]),
        Index(value = ["isCurrentlyReading"]),
        Index(value = ["lastRead"])
    ]
)
data class LibraryItem(
    @PrimaryKey
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val progress: Int = 0,
    val isCurrentlyReading: Boolean = false,
    val currentChapter: String = "",
    val currentChapterUrl: String = "",
    val totalChapters: Int = 0,
    val contentType: ContentType = ContentType.WEB,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = System.currentTimeMillis(),
    val isDownloading: Boolean = false,
    val lastScrollPosition: Float = 0f,
    val lastReadIndex: Int = 0,
    val lastReadElementKey: String = "",
    val lastReadOffsetFraction: Float = FRACTION_UNKNOWN,
    val hasUpdates: Boolean = false,
    val chapterSummaries: Map<String, String> = emptyMap(),
    val baseTitle: String = "",
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val baseNovelUrl: String = "",
    val sourceName: String = "",
    val isDownloaded: Boolean = false,
    val downloadedAt: Long? = null,
    val coverImageUrl: String = ""
) {
    init {
        require(title.isNotBlank()) { "Title cannot be blank" }
        require(url.isNotBlank()) { "URL cannot be blank" }
        require(progress in 0..100) { "Progress must be between 0 and 100, got: $progress" }
        require(timestamp > 0) { "Timestamp must be positive" }
    }

    fun withProgress(newProgress: Int): LibraryItem {
        val clampedProgress = newProgress.coerceIn(0, 100)
        return copy(progress = clampedProgress)
    }

    fun markAsReading(): LibraryItem = copy(isCurrentlyReading = true)

    fun markAsNotReading(): LibraryItem = copy(isCurrentlyReading = false)

    fun isStarted(): Boolean = progress > 0
}
