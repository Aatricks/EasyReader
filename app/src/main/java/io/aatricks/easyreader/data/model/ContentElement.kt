package io.aatricks.easyreader.data.model

import kotlinx.serialization.Serializable

/**
 * Sealed class representing different types of content elements that can appear in a chapter.
 * Allows for type-safe handling of mixed content (text and images).
 */
@Serializable
sealed class ContentElement {
    /**
     * Placeholder content element for loading states
     * @property text The placeholder text
     * @property heightDp The height in dp to reserve
     */
    @Serializable
    data class Placeholder(val text: String, val heightDp: Int = 1000) : ContentElement()

    /**
     * Represents all content for a single page, containing sub-elements
     * @property elements The list of text and image elements on the page
     */
    @Serializable
    data class PageContent(val elements: List<ContentElement>) : ContentElement()

    /**
     * Text content element
     * @property content The text content, typically a paragraph
     */
    @Serializable
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
     * @property width Image width in pixels (0 if unknown)
     * @property height Image height in pixels (0 if unknown)
     * @property side Which part of the image to display (for split pages)
     */
    @Serializable
    data class Image(
        val url: String,
        val altText: String? = null,
        val caption: String? = null,
        val description: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val side: Side = Side.FULL
    ) : ContentElement() {
        @Serializable
        enum class Side { FULL, LEFT, RIGHT }

        init {
            require(url.isNotBlank()) { "Image URL cannot be blank" }
        }
    }

    /**
     * Group of images that should be displayed together as a single page
     * @property images List of images in the group
     */
    @Serializable
    data class ImageGroup(
        val images: List<Image>
    ) : ContentElement() {
        init {
            require(images.isNotEmpty()) { "Image group cannot be empty" }
        }
    }
    
    /**
     * Returns true if this element is text content
     */
    fun isText(): Boolean = this is Text
    
    /**
     * Returns true if this element is image content
     */
    fun isImage(): Boolean = this is Image || this is ImageGroup
}
