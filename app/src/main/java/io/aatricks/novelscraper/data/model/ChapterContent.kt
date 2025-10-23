package io.aatricks.novelscraper.data.model

/**
 * Data class representing the content of a chapter or section.
 * Contains a list of content elements (text and images) along with metadata.
 *
 * @property paragraphs List of content elements (text paragraphs and images) in order
 * @property title Optional chapter title
 * @property url The source URL of the chapter
 * @property chapterNumber Optional chapter number for ordering
 * @property nextChapterUrl Optional URL to the next chapter for navigation
 * @property previousChapterUrl Optional URL to the previous chapter for navigation
 */
data class ChapterContent(
    val paragraphs: List<ContentElement>,
    val title: String? = null,
    val url: String,
    val chapterNumber: Int? = null,
    val nextChapterUrl: String? = null,
    val previousChapterUrl: String? = null
) {
    init {
        require(url.isNotBlank()) { "URL cannot be blank" }
    }
    
    /**
     * Returns true if the chapter has content
     */
    fun hasContent(): Boolean = paragraphs.isNotEmpty()
    
    /**
     * Returns the number of text elements in the chapter
     */
    fun getTextCount(): Int = paragraphs.count { it is ContentElement.Text }
    
    /**
     * Returns the number of image elements in the chapter
     */
    fun getImageCount(): Int = paragraphs.count { it is ContentElement.Image }
    
    /**
     * Returns true if there is a next chapter available
     */
    fun hasNextChapter(): Boolean = !nextChapterUrl.isNullOrBlank()
    
    /**
     * Returns true if there is a previous chapter available
     */
    fun hasPreviousChapter(): Boolean = !previousChapterUrl.isNullOrBlank()
    
    /**
     * Get all text content concatenated together
     */
    fun getAllText(): String = paragraphs
        .filterIsInstance<ContentElement.Text>()
        .joinToString("\n\n") { it.content }
    
    /**
     * Get all image URLs
     */
    fun getAllImageUrls(): List<String> = paragraphs
        .filterIsInstance<ContentElement.Image>()
        .map { it.url }
}
