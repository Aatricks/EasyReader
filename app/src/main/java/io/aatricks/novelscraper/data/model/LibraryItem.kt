package io.aatricks.novelscraper.data.model

/**
 * Data class representing a library item in the Novel Scraper app.
 * Immutable by default with validation in init block.
 *
 * @property title The title of the novel or document
 * @property url The source URL or file path
 * @property timestamp Unix timestamp when the item was added to library
 * @property type The type of content (Web, PDF, or HTML)
 * @property progress Reading progress as percentage (0-100)
 * @property isCurrentlyReading Flag indicating if this is the currently active reading item
 * @property isSelected UI selection state for batch operations
 */
data class LibraryItem(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: ContentType = ContentType.WEB,
    val progress: Int = 0,
    val isCurrentlyReading: Boolean = false,
    val isSelected: Boolean = false,
    val currentChapter: String = "",
    val currentChapterUrl: String = "",
    val totalChapters: Int = 0,
    val contentType: ContentType = ContentType.WEB,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = System.currentTimeMillis(),
    val isDownloading: Boolean = false,
    val lastScrollPosition: Int = 0,
    val chapterSummaries: Map<String, String>? = emptyMap(), // chapter URL -> AI-generated summary (nullable for Gson backward compatibility)
    val baseTitle: String = "" // Base title without chapter markers - used for grouping (empty for backward compatibility)
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
     * Toggles the selection state of this item.
     */
    fun toggleSelection(): LibraryItem = copy(isSelected = !isSelected)
    
    /**
     * Checks if the item has been started (progress > 0).
     */
    fun isStarted(): Boolean = progress > 0
    
    /**
     * Checks if the item is completed (progress == 100).
     */
    fun isCompleted(): Boolean = progress == 100
}
