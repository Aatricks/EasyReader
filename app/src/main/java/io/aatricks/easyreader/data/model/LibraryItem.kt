package io.aatricks.easyreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Data class representing a library item in the Novel Scraper app.
 * Immutable by default with validation in init block.
 */
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
    val lastReadOffset: Int = 0,
    val lastReadOffsetFraction: Float? = null,
    val hasUpdates: Boolean = false,
    val chapterSummaries: Map<String, String> = emptyMap(),
    val baseTitle: String = "",
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val baseNovelUrl: String = "",
    val sourceName: String = ""
) {
    init {
        require(title.isNotBlank()) { "Title cannot be blank" }
        require(url.isNotBlank()) { "URL cannot be blank" }
        require(progress in 0..100) { "Progress must be between 0 and 100, got: $progress" }
        require(timestamp > 0) { "Timestamp must be positive" }
    }
    
    /**
     * Creates a copy of this LibraryItem with updated progress.
     * Ensures progress stays within valid range (0-100).
     */
    fun withProgress(newProgress: Int): LibraryItem {
        val clampedProgress = newProgress.coerceIn(0, 100)
        return copy(progress = clampedProgress)
    }
    
    /**
     * Creates a copy marking this item as currently reading.
     * Typically used when opening a chapter.
     */
    fun markAsReading(): LibraryItem = copy(isCurrentlyReading = true)
    
    /**
     * Creates a copy marking this item as not currently reading.
     */
    fun markAsNotReading(): LibraryItem = copy(isCurrentlyReading = false)
    
    /**
     * Checks if the item has been started (progress > 0).
     */
    fun isStarted(): Boolean = progress > 0
    
    /**
     * Checks if the item is completed (progress == 100).
     */
    fun isCompleted(): Boolean = progress == 100
}
