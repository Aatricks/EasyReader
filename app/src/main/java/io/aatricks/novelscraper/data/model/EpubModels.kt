package io.aatricks.novelscraper.data.model

/**
 * Data classes for EPUB structure and metadata
 */

/**
 * Represents EPUB metadata
 */
data class EpubMetadata(
    val title: String,
    val author: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val identifier: String? = null
)

/**
 * Represents a table of contents item with hierarchical structure
 */
data class EpubTocItem(
    val id: String,
    val title: String,
    val href: String,
    val playOrder: Int = 0,
    val children: List<EpubTocItem> = emptyList()
) {
    /**
     * Check if this TOC item has children
     */
    fun hasChildren(): Boolean = children.isNotEmpty()
    
    /**
     * Get all TOC items in a flat list (including nested items)
     */
    fun flatten(): List<EpubTocItem> {
        val result = mutableListOf(this)
        children.forEach { child ->
            result.addAll(child.flatten())
        }
        return result
    }
    
    /**
     * Find a TOC item by href
     */
    fun findByHref(href: String): EpubTocItem? {
        if (this.href == href) return this
        children.forEach { child ->
            val found = child.findByHref(href)
            if (found != null) return found
        }
        return null
    }
}

/**
 * Represents an EPUB chapter with content
 */
data class EpubChapter(
    val href: String,
    val title: String? = null,
    val content: List<ContentElement> = emptyList(),
    val nextHref: String? = null,
    val previousHref: String? = null
) {
    /**
     * Check if chapter has content
     */
    fun hasContent(): Boolean = content.isNotEmpty()
    
    /**
     * Get all text content concatenated
     */
    fun getAllText(): String = content
        .filterIsInstance<ContentElement.Text>()
        .joinToString("\n\n") { it.content }
    
    /**
     * Get all image URLs
     */
    fun getAllImageUrls(): List<String> = content
        .filterIsInstance<ContentElement.Image>()
        .map { it.url }
}

/**
 * Represents the complete EPUB book structure
 */
data class EpubBook(
    val metadata: EpubMetadata,
    val toc: List<EpubTocItem> = emptyList(),
    val spine: List<String> = emptyList(), // List of hrefs in reading order
    val manifest: Map<String, String> = emptyMap() // id to href mapping
) {
    /**
     * Get all TOC items in a flat list
     */
    fun getFlatToc(): List<EpubTocItem> = toc.flatMap { it.flatten() }
    
    /**
     * Find TOC item by href
     */
    fun findTocItemByHref(href: String): EpubTocItem? {
        toc.forEach { item ->
            val found = item.findByHref(href)
            if (found != null) return found
        }
        return null
    }
    
    /**
     * Get next chapter href in spine order
     */
    fun getNextHref(currentHref: String): String? {
        val index = spine.indexOf(currentHref)
        return if (index >= 0 && index < spine.size - 1) {
            spine[index + 1]
        } else null
    }
    
    /**
     * Get previous chapter href in spine order
     */
    fun getPreviousHref(currentHref: String): String? {
        val index = spine.indexOf(currentHref)
        return if (index > 0) {
            spine[index - 1]
        } else null
    }
}
