package io.aatricks.easyreader.data.model

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
        if (hrefsMatch(this.href, href)) return this
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
    fun getAllImageUrls(): List<String> = content.flatMap { 
        when (it) {
            is ContentElement.Image -> listOf(it.url)
            is ContentElement.ImageGroup -> it.images.map { img -> img.url }
            else -> emptyList()
        }
    }
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
     * Resolve any spine href (including sub-anchors or mid-chapter split segments)
     * to the href of the TOC entry that owns it — the last TOC entry whose spine
     * position is at or before the given href. Returns null if no TOC entry maps.
     */
    fun findContainingTocHref(href: String): String? {
        if (toc.isEmpty() || spine.isEmpty()) return null
        val targetSpineIndex = spine.indexOfFirst { hrefsMatch(it, href) }
        return getFlatToc()
            .takeIf { targetSpineIndex >= 0 }
            ?.mapNotNull { item ->
                val idx = spine.indexOfFirst { hrefsMatch(it, item.href) }
                if (idx >= 0) item to idx else null
            }
            ?.sortedBy { it.second }
            ?.lastOrNull { it.second <= targetSpineIndex }
            ?.first
            ?.href
    }

    /**
     * Get the first document that should be opened for reading.
     */
    fun getFirstReadableHref(): String? =
        getReadableNavigationHrefs().firstOrNull()
            ?: getFlatToc().firstOrNull()?.href
            ?: spine.firstOrNull()
    
    /**
     * Get next chapter href in spine order
     */
    fun getNextHref(currentHref: String): String? {
        val readableHrefs = getReadableNavigationHrefs()
        if (readableHrefs.any { hrefsMatch(it, currentHref) }) {
            return getAdjacentHref(readableHrefs, currentHref, direction = 1)
        }

        val navigationHrefs = getNavigationHrefs()
        return getAdjacentHref(navigationHrefs, currentHref, direction = 1)
            ?: getAdjacentHref(spine, currentHref, direction = 1)
    }
    
    /**
     * Get previous chapter href in spine order
     */
    fun getPreviousHref(currentHref: String): String? {
        val readableHrefs = getReadableNavigationHrefs()
        if (readableHrefs.any { hrefsMatch(it, currentHref) }) {
            return getAdjacentHref(readableHrefs, currentHref, direction = -1)
        }

        val navigationHrefs = getNavigationHrefs()
        return getAdjacentHref(navigationHrefs, currentHref, direction = -1)
            ?: getAdjacentHref(spine, currentHref, direction = -1)
    }

    private fun getNavigationHrefs(): List<String> =
        getFlatToc().map { it.href }.distinctBy(::normalizeHref)

    private fun getReadableNavigationHrefs(): List<String> {
        val tocItems = getFlatToc()
        val firstReadableIndex = tocItems.indexOfFirst { it.isLikelyContentTocItem() }
            .takeIf { it >= 0 }
            ?: tocItems.indexOfFirst { !it.isKnownNonReadingTocItem() }
                .takeIf { it >= 0 }
            ?: return emptyList()

        return tocItems
            .drop(firstReadableIndex)
            .filterNot { it.isKnownNonReadingTocItem() }
            .map { it.href }
            .distinctBy(::normalizeHref)
    }

    private fun getAdjacentHref(hrefs: List<String>, currentHref: String, direction: Int): String? {
        val index = hrefs.indexOfFirst { hrefsMatch(it, currentHref) }
        if (index < 0) return null
        val adjacentIndex = index + direction
        return hrefs.getOrNull(adjacentIndex)
    }
}

private fun hrefsMatch(first: String, second: String): Boolean =
    normalizeHref(first) == normalizeHref(second)

private fun normalizeHref(href: String): String =
    href.replace("\\", "/")
        .removePrefix("/")
        .substringBefore("#")

private fun EpubTocItem.isLikelyContentTocItem(): Boolean {
    val normalizedTitle = title.normalizeTitle()
    return READABLE_TOC_TITLE_PATTERNS.any { it.containsMatchIn(normalizedTitle) }
}

private fun EpubTocItem.isKnownNonReadingTocItem(): Boolean =
    title.normalizeTitle() in NON_READING_TOC_TITLES

private fun String.normalizeTitle(): String =
    trim()
        .lowercase()
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")

private val READABLE_TOC_TITLE_PATTERNS = listOf(
    Regex("""^\d+(?:[.)]|:|\s)"""),
    Regex("""^(chapter|chapitre|prologue|epilogue|afterword|bonus|interlude|side story|act|part|book|volume)\b""")
)

private val NON_READING_TOC_TITLES = setOf(
    "cover",
    "title page",
    "contents",
    "table of contents",
    "table of contents page",
    "toc",
    "sommaire",
    "color gallery",
    "characters",
    "copyright",
    "copyrights",
    "copyrights and credits",
    "credits",
    "newsletter"
)
