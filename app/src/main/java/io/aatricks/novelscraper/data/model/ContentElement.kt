package io.aatricks.novelscraper.data.model

/**
 * Sealed class representing different types of content elements that can appear in a chapter.
 * Allows for type-safe handling of mixed content (text and images).
 */
sealed class ContentElement {
    /**
     * Text content element
     * @property content The text content, typically a paragraph
     */
    data class Text(val content: String) : ContentElement() {
        init {
            require(content.isNotEmpty()) { "Text content cannot be empty" }
        }
    }
    
    /**
     * Image content element
     * @property url The URL or path to the image
     * @property altText Optional alternative text for accessibility
     * @property caption Optional image caption
     */
    data class Image(
        val url: String,
        val altText: String? = null,
        val caption: String? = null,
        val description: String? = null
    ) : ContentElement() {
        init {
            require(url.isNotBlank()) { "Image URL cannot be blank" }
        }
    }
    
    /**
     * Returns true if this element is text content
     */
    fun isText(): Boolean = this is Text
    
    /**
     * Returns true if this element is image content
     */
    fun isImage(): Boolean = this is Image
}
