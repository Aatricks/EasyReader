package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ChapterContent
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.util.TextUtils
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ViewModel for the reader screen.
 * Manages content loading, navigation, and reading progress.
 */
class ReaderViewModel(
    val contentRepository: ContentRepository,
    private val libraryRepository: LibraryRepository,
    private val exploreRepository: io.aatricks.novelscraper.data.repository.ExploreRepository
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // Current library item ID being read
    private var currentLibraryItemId: String? = null
    // Throttle auto navigation to avoid repeated triggers
    private var lastAutoNavigateAt: Long = 0L
    // Suppress auto navigation when restoring a saved position until user interacts
    private var suppressAutoNavUntilUserInteraction: Boolean = false
    private var restoredScrollPercent: Float = 0f
    // Track if we're explicitly navigating (not restoring from library)
    private var isExplicitNavigation: Boolean = false
    // Track last raw scroll offset (pixels) to detect actual user gesture direction
    private var lastRawScrollOffset: Float = -1f
    // Debounce progress updates to reduce jitter
    private var progressUpdateJob: Job? = null

    /**
     * Data class representing the reader UI state
     */
    data class ReaderUiState(
        val content: ChapterContent? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val scrollPosition: Float = 0f,
        val scrollProgress: Int = 0, // 0-100 percentage
        val scrollIndex: Int = 0, // First visible item index
        val scrollOffset: Int = 0, // First visible item offset
        val isScrollingDown: Boolean = true,
        val hasReachedQuarterScreen: Boolean = false,
        val canNavigateNext: Boolean = false,
        val canNavigatePrevious: Boolean = false,
        val showControls: Boolean = false, // Show/hide bottom navigation bar
        val novelName: String = "", // Novel/book title
        val chapterTitle: String = "", // Current chapter title
        val baseTitle: String = "", // Base title for chapter lookup
        val baseNovelUrl: String = "", // URL of the novel main page
        val sourceName: String = "", // Name of the source
        val isPagedMode: Boolean = false, // Toggle between vertical scroll and horizontal paging
        val isRtl: Boolean = true, // Right-to-Left swipe for paged mode
        val fullChapterList: List<io.aatricks.novelscraper.data.model.ChapterInfo> = emptyList(),
        val isChaptersLoading: Boolean = false,
        val seekTrigger: Long = 0L // Timestamp to trigger seek in UI
    )

    /**
     * Load content from URL or file path
     * @param url The URL or file path to load content from
     * @param libraryItemId Optional library item ID to track reading progress
     */
    fun loadContent(url: String, libraryItemId: String? = null) {
        viewModelScope.launch {
            try {
                // Check if this is an EPUB URL with href fragment (format: path#href or content://...#href)
                if (url.contains("#")) {
                    val parts = url.split("#", limit = 2)
                    if (parts.size == 2) {
                        val basePath = parts[0]
                        val href = parts[1]
                        // Check if base path looks like an EPUB (content URI or .epub file)
                        if (basePath.startsWith("content://") || basePath.endsWith(".epub", ignoreCase = true) || basePath.contains("epub")) {
                            loadEpubChapter(basePath, href, libraryItemId)
                            return@launch
                        }
                    }
                }
                
                // Save previous progress for the current library item before loading next
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                val prevProgress = _uiState.value.scrollProgress
                if (prevItemId != null && prevContent != null) {
                    try {
                        // Only update progress and scroll position, NOT currentChapter
                        // currentChapter should remain the clean label set during item creation
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = "", // Empty string signals to keep existing value
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition.toInt(),
                            lastReadIndex = _uiState.value.scrollIndex,
                            lastReadOffset = _uiState.value.scrollOffset
                        )
                    } catch (_: Exception) {}
                }

                _uiState.update { it.copy(isLoading = true, error = null) }
                currentLibraryItemId = libraryItemId

                when (val result = contentRepository.loadContent(url)) {
                    is ContentRepository.ContentResult.Success -> {
                        val content = ChapterContent(
                            paragraphs = result.elements,
                            title = result.title,
                            url = result.url,
                            nextChapterUrl = contentRepository.incrementChapterUrl(result.url),
                            previousChapterUrl = contentRepository.decrementChapterUrl(result.url)
                        )

                        // Get novel name and chapter info
                        val libraryItem = libraryItemId?.let { libraryRepository.getItemById(it) }
                        val novelName = libraryItem?.baseTitle?.ifBlank { libraryItem.title } ?: content.title ?: ""
                        val chapterTitle = content.title ?: libraryItem?.currentChapter ?: ""
                        val baseTitle = libraryItem?.baseTitle ?: extractBaseTitle(novelName, ContentType.WEB)
                        val baseNovelUrl = libraryItem?.baseNovelUrl ?: ""
                        val sourceName = libraryItem?.sourceName ?: ""
                        
                        // Determine reading mode: Priority to saved preference, then guess
                        val savedMode = libraryItem?.readingMode
                        val isPaged = if (savedMode != null) {
                            savedMode == io.aatricks.novelscraper.data.model.ReadingMode.PAGED
                        } else {
                            guessIsPaged(content)
                        }

                        _uiState.update {
                            it.copy(
                                content = content,
                                isLoading = false,
                                error = null,
                                canNavigateNext = content.hasNextChapter(),
                                canNavigatePrevious = content.hasPreviousChapter(),
                                scrollPosition = 0f,
                                scrollProgress = 0,
                                scrollIndex = 0,
                                scrollOffset = 0,
                                hasReachedQuarterScreen = false,
                                novelName = novelName,
                                chapterTitle = chapterTitle,
                                baseTitle = baseTitle,
                                baseNovelUrl = baseNovelUrl,
                                sourceName = sourceName,
                                isPagedMode = isPaged
                            )
                        }

                        // Load full chapter list if available
                        libraryItem?.let { item ->
                            if (item.baseNovelUrl.isNotBlank() && item.sourceName.isNotBlank()) {
                                loadFullChapterList(item.baseNovelUrl, item.sourceName)
                            }
                        }

                        // Mark as currently reading in library and restore saved progress if not explicit navigation
                        libraryItemId?.let {
                            libraryRepository.markAsCurrentlyReading(it)
                            if (!isExplicitNavigation) {
                                val item = libraryRepository.getItemById(it)
                                item?.let { libItem ->
                                    // Restore last known saved chapter percent/position for this library item
                                    restoredScrollPercent = libItem.lastScrollPosition.toFloat()
                                    suppressAutoNavUntilUserInteraction = true
                                    _uiState.update { state ->
                                        state.copy(
                                            scrollPosition = restoredScrollPercent,
                                            scrollProgress = libItem.progress,
                                            scrollIndex = libItem.lastReadIndex,
                                            scrollOffset = libItem.lastReadOffset
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Reset explicit navigation flag
                        isExplicitNavigation = false
                    }
                    is ContentRepository.ContentResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = result.message
                            )
                        }
                        // Reset explicit navigation flag on error
                        isExplicitNavigation = false
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load content: ${e.message}"
                    )
                }
                // Reset explicit navigation flag on error
                isExplicitNavigation = false
            }
        }
    }

    /**
     * Navigate to the next chapter
     * Automatically adds the chapter to library if it doesn't exist
     */
    fun navigateToNextChapter() {
        val nextUrl = _uiState.value.content?.nextChapterUrl
        if (nextUrl != null) {
            viewModelScope.launch {
                // Mark as explicit navigation to prevent scroll restoration
                isExplicitNavigation = true
                
                // Get current library item to extract baseTitle and other metadata
                val currentItem = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
                
                // Check if next chapter already exists in library
                val existingNextItem = libraryRepository.getItemByUrl(nextUrl)
                
                val nextItemId = if (existingNextItem != null) {
                    // Chapter already exists, use its ID
                    existingNextItem.id
                } else if (currentItem != null && currentItem.contentType == ContentType.WEB) {
                    // Add new chapter to library with same baseTitle as current chapter
                    try {
                        val fetchedTitle = contentRepository.fetchTitle(nextUrl) ?: nextUrl
                        val chapterLabel = extractChapterLabel(fetchedTitle) 
                            ?: extractChapterLabelFromUrl(nextUrl) 
                            ?: "Chapter ${extractChapterNumber(currentItem.currentChapter)?.plus(1) ?: 1}"
                        
                        // Get base title from current item, or extract it if empty
                        val baseTitle = if (currentItem.baseTitle.isNotBlank()) {
                            currentItem.baseTitle
                        } else {
                            extractBaseTitle(currentItem.title, ContentType.WEB)
                        }
                        
                        val newItem = libraryRepository.addItem(
                            title = fetchedTitle.trim().ifBlank { "$baseTitle - $chapterLabel" },
                            url = nextUrl,
                            contentType = ContentType.WEB,
                            currentChapter = chapterLabel,
                            baseTitle = baseTitle,
                            baseNovelUrl = currentItem.baseNovelUrl,
                            sourceName = currentItem.sourceName
                        )
                        // Inherit reading mode
                        libraryRepository.updateReadingMode(newItem.id, currentItem.readingMode)
                        newItem.id
                    } catch (e: Exception) {
                        // Failed to add, load without library tracking
                        null
                    }
                } else {
                    // Not a WEB item or no current item, load without library tracking
                    null
                }
                
                // Load the next chapter content
                loadContent(nextUrl, nextItemId)
            }
        }
    }
    
    /**
     * Navigate to the previous chapter
     * Automatically adds the chapter to library if it doesn't exist
     */
    fun navigateToPreviousChapter() {
        val prevUrl = _uiState.value.content?.previousChapterUrl
        if (prevUrl != null) {
            viewModelScope.launch {
                // Mark as explicit navigation to prevent scroll restoration
                isExplicitNavigation = true
                
                // Get current library item to extract baseTitle and other metadata
                val currentItem = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
                
                // Check if previous chapter already exists in library
                val existingPrevItem = libraryRepository.getItemByUrl(prevUrl)
                
                val prevItemId = if (existingPrevItem != null) {
                    // Chapter already exists, use its ID
                    existingPrevItem.id
                } else if (currentItem != null && currentItem.contentType == ContentType.WEB) {
                    // Add new chapter to library with same baseTitle as current chapter
                    try {
                        val fetchedTitle = contentRepository.fetchTitle(prevUrl) ?: prevUrl
                        val chapterLabel = extractChapterLabel(fetchedTitle) 
                            ?: extractChapterLabelFromUrl(prevUrl) 
                            ?: "Chapter ${extractChapterNumber(currentItem.currentChapter)?.minus(1) ?: 1}"
                        
                        // Get base title from current item, or extract it if empty
                        val baseTitle = if (currentItem.baseTitle.isNotBlank()) {
                            currentItem.baseTitle
                        } else {
                            extractBaseTitle(currentItem.title, ContentType.WEB)
                        }
                        
                        val newItem = libraryRepository.addItem(
                            title = fetchedTitle.trim().ifBlank { "$baseTitle - $chapterLabel" },
                            url = prevUrl,
                            contentType = ContentType.WEB,
                            currentChapter = chapterLabel,
                            baseTitle = baseTitle,
                            baseNovelUrl = currentItem.baseNovelUrl,
                            sourceName = currentItem.sourceName
                        )
                        // Inherit reading mode
                        libraryRepository.updateReadingMode(newItem.id, currentItem.readingMode)
                        newItem.id
                    } catch (e: Exception) {
                        // Failed to add, load without library tracking
                        null
                    }
                } else {
                    // Not a WEB item or no current item, load without library tracking
                    null
                }
                
                // Load the previous chapter content
                loadContent(prevUrl, prevItemId)
            }
        }
    }
    
    /**
     * Load a specific EPUB chapter by href
     * @param epubPath The path to the EPUB file
     * @param href The chapter href within the EPUB
     * @param libraryItemId Optional library item ID to track reading progress
     */
    fun loadEpubChapter(epubPath: String, href: String, libraryItemId: String? = null) {
        viewModelScope.launch {
            try {
                // Save previous progress for the current library item before loading next
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                if (prevItemId != null && prevContent != null) {
                    try {
                        // Only update progress and scroll position, NOT currentChapter
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = "", // Empty string signals to keep existing value
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition.toInt(),
                            lastReadIndex = _uiState.value.scrollIndex,
                            lastReadOffset = _uiState.value.scrollOffset
                        )
                    } catch (_: Exception) {}
                }

                _uiState.update { it.copy(isLoading = true, error = null) }
                currentLibraryItemId = libraryItemId

                // Get EPUB book structure
                val epubBook = contentRepository.getEpubBook(epubPath)
                if (epubBook == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to load EPUB structure"
                        )
                    }
                    return@launch
                }

                // Load chapter content with full ContentElements (text + images)
                val chapter = contentRepository.loadEpubChapterFull(epubPath, href)
                if (chapter == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to load chapter content"
                        )
                    }
                    return@launch
                }
                
                // Prefer the next/previous hrefs returned by the chapter loader (these
                // account for merged chapters). Fall back to spine-based lookup if absent.
                // Format text runs in EPUB chapter content so the UI sees the
                // same formatted paragraphs as the preview generator. We need
                // to preserve image positions, so we flush consecutive text
                // runs through the formatter and emit images as-is.
                val formattedElements = mutableListOf<ContentElement>()
                val textBuffer = mutableListOf<String>()

                fun flushTextBuffer() {
                    if (textBuffer.isEmpty()) return
                    val joined = textBuffer.joinToString("\n\n")
                    val formatted = TextUtils.formatChapterText(joined)
                    val parts = formatted.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
                    parts.forEach { p -> formattedElements.add(ContentElement.Text(p)) }
                    textBuffer.clear()
                }

                for (el in chapter.content) {
                    when (el) {
                        is ContentElement.Text -> textBuffer.add(el.content)
                        is ContentElement.Image -> {
                            flushTextBuffer()
                            formattedElements.add(el)
                        }
                    }
                }
                flushTextBuffer()

                val content = ChapterContent(
                    paragraphs = formattedElements,
                    title = chapter.title,
                    url = "$epubPath#$href",
                    nextChapterUrl = chapter.nextHref?.let { "$epubPath#${it}" }
                        ?: epubBook.getNextHref(href)?.let { "$epubPath#${it}" },
                    previousChapterUrl = chapter.previousHref?.let { "$epubPath#${it}" }
                        ?: epubBook.getPreviousHref(href)?.let { "$epubPath#${it}" }
                )

                // Get novel name and chapter info
                val libraryItem = libraryItemId?.let { libraryRepository.getItemById(it) }
                val novelName = libraryItem?.baseTitle?.ifBlank { libraryItem.title } ?: content.title ?: ""
                val chapterTitle = content.title ?: libraryItem?.currentChapter ?: ""
                
                _uiState.update {
                    it.copy(
                        content = content,
                        isLoading = false,
                        error = null,
                        canNavigateNext = content.hasNextChapter(),
                        canNavigatePrevious = content.hasPreviousChapter(),
                        scrollPosition = 0f,
                        scrollProgress = 0,
                        scrollIndex = 0,
                        scrollOffset = 0,
                        hasReachedQuarterScreen = false,
                        novelName = novelName,
                        chapterTitle = chapterTitle
                    )
                }

                // Mark as currently reading in library
                libraryItemId?.let {
                    libraryRepository.markAsCurrentlyReading(it)
                    // Only restore scroll position if we haven't explicitly reset it (i.e., not from navigation)
                    if (!isExplicitNavigation) {
                        val item = libraryRepository.getItemById(it)
                        item?.let { libItem ->
                            restoredScrollPercent = libItem.lastScrollPosition.toFloat()
                            suppressAutoNavUntilUserInteraction = true
                            _uiState.update { state ->
                                state.copy(
                                    scrollPosition = restoredScrollPercent,
                                    scrollProgress = libItem.progress,
                                    scrollIndex = libItem.lastReadIndex,
                                    scrollOffset = libItem.lastReadOffset
                                )
                            }
                        }
                    }
                }
                
                // Reset explicit navigation flag
                isExplicitNavigation = false
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load EPUB chapter: ${e.message}"
                    )
                }
                // Reset explicit navigation flag on error
                isExplicitNavigation = false
            }
        }
    }

    /**
     * Update scroll position and calculate progress
     * @param scrollOffset Current scroll offset
     * @param maxScrollOffset Maximum possible scroll offset
     * @param viewportHeight Height of the visible viewport
     * @param index First visible item index
     * @param offset First visible item scroll offset
     */
    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int
    ) {
        // Cancel previous update and schedule new one with debounce
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            delay(100) // 100ms debounce to reduce jitter
            performScrollUpdate(scrollOffset, maxScrollOffset, viewportHeight, index, offset)
        }
    }

    private fun performScrollUpdate(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int
    ) {
        // Determine raw delta to detect true user gesture direction
        val deltaRaw = if (lastRawScrollOffset < 0f) {
            0f
        } else {
            scrollOffset - lastRawScrollOffset
        }

        // Determine scroll direction from raw delta
        val isScrollingDown = deltaRaw > 0f

        // Calculate progress percentage as normalized percent (0-100)
        val progress = if (maxScrollOffset > 0) {
            ((scrollOffset / maxScrollOffset) * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }

        // hasReachedQuarterScreen interpreted as percent >= 25%
        val hasReached = progress >= 25f

        // Update UI state: use scrollPosition as percent (Float), scrollProgress as Int
        val progressInt = progress.toInt()
        _uiState.update {
            it.copy(
                scrollPosition = progress,
                scrollProgress = progressInt,
                scrollIndex = index,
                scrollOffset = offset,
                isScrollingDown = isScrollingDown,
                hasReachedQuarterScreen = hasReached
            )
        }

        // Update reading progress in library when reaching milestones (every 2%)
        if (progressInt > 0 && progressInt % 2 == 0) {
            updateReadingProgress(progressInt)
        }

        // Auto-navigation logic: suppressed until user moves sufficiently away from restored percent
        val now = System.currentTimeMillis()
        if (suppressAutoNavUntilUserInteraction) {
            // If user moved more than 2 percentage points away, clear suppression
            if (abs(progress - restoredScrollPercent) > 2f) {
                suppressAutoNavUntilUserInteraction = false
            } else {
                // still suppressed; update lastRawScrollOffset and do not auto-navigate
                lastRawScrollOffset = scrollOffset
                return
            }
        }

        // Auto-navigation disabled: chapters change only via explicit user actions (button taps)
        // This prevents unwanted navigation when scrolling to read the end of a chapter

        // Update last raw scroll offset for next direction calculation
        lastRawScrollOffset = scrollOffset
    }

    /**
     * Update reading progress in the library
     * @param progress Progress percentage (0-100)
     */
    fun updateReadingProgress(progress: Int) {
        try {
            currentLibraryItemId?.let { itemId ->
                val currentChapterUrl = _uiState.value.content?.url ?: ""
                val lastScroll = _uiState.value.scrollPosition.toInt()
                val index = _uiState.value.scrollIndex
                val offset = _uiState.value.scrollOffset

                libraryRepository.saveProgress(
                    itemId = itemId,
                    currentChapter = "", // Don't update currentChapter label
                    progress = progress,
                    currentChapterUrl = currentChapterUrl,
                    lastScrollProgress = lastScroll,
                    lastReadIndex = index,
                    lastReadOffset = offset
                )
            }
        } catch (e: Exception) {
            // Silently fail progress updates to not interrupt reading
        }
    }

    /**
     * Extract chapter label from title
     */
    private fun extractChapterLabel(title: String?): String? {
        if (title == null) return null
        val regex = Regex("(chapter|ch|ch\\.)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        return match?.let { "Chapter ${it.groupValues[2]}" }
    }

    /**
     * Extract chapter label from URL
     */
    private fun extractChapterLabelFromUrl(url: String): String? {
        val patterns = listOf(
            Regex("chapter\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("ch(?:apter)?\\D*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("/(\\d+)(?:/|$)"),
            Regex("-(\\d+)(?:\\D|$)")
        )
        for (r in patterns) {
            val m = r.find(url)
            if (m != null && m.groupValues.size >= 2) {
                val num = m.groupValues[1]
                return "Chapter $num"
            }
        }
        return null
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Retry loading content after an error
     */
    fun retryLoad() {
        val url = _uiState.value.content?.url
        if (url != null) {
            loadContent(url, currentLibraryItemId)
        }
    }

    /**
     * Reset reader state (call when navigating away)
     */
    fun resetState() {
        _uiState.value = ReaderUiState()
        currentLibraryItemId = null
    }

    /**
     * Check if content is cached
     */
    fun isContentCached(url: String): Boolean {
        return contentRepository.isCached(url)
    }

    /**
     * Clear cache for specific URL
     */
    fun clearCache(url: String) {
        viewModelScope.launch {
            try {
                contentRepository.clearCache(url)
            } catch (e: Exception) {
                // Silently fail cache operations
            }
        }
    }

    /**
     * Clear all cache
     */
    fun clearAllCache() {
        viewModelScope.launch {
            try {
                contentRepository.clearAllCache()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to clear cache: ${e.message}")
                }
            }
        }
    }

    /**
     * Get cache size in bytes
     */
    suspend fun getCacheSize(): Long {
        return contentRepository.getCacheSize()
    }

    /**
     * Save current scroll position (e.g., for configuration changes)
     */
    fun saveScrollPosition(position: Float) {
        _uiState.update { it.copy(scrollPosition = position) }
    }

    /**
     * Restore scroll position
     */
    fun getScrollPosition(): Float {
        return _uiState.value.scrollPosition
    }

    /**
     * Seek to a specific progress percentage (0-100)
     */
    fun seekToProgress(progress: Int) {
        val targetPercent = progress.coerceIn(0, 100).toFloat()
        val totalItems = _uiState.value.content?.paragraphs?.size ?: 0
        val roughIndex = if (totalItems > 0) {
            ((targetPercent / 100f) * totalItems).toInt().coerceIn(0, totalItems - 1)
        } else 0

        _uiState.update { it.copy(
            scrollPosition = targetPercent, 
            scrollProgress = progress,
            scrollIndex = roughIndex,
            scrollOffset = 0,
            seekTrigger = System.currentTimeMillis()
        ) }
        
        // When seeking via slider, we should update reading progress in library
        updateReadingProgress(progress)
    }

    override fun onCleared() {
        super.onCleared()
        // Save final reading progress before clearing
        val progress = _uiState.value.scrollProgress
        if (progress > 0) {
            updateReadingProgress(progress)
        }
    }
    
    /**
     * Extract base title by removing chapter markers
     * Only normalizes WEB content - PDFs/HTML/EPUB keep full titles
     */
    private fun extractBaseTitle(title: String, contentType: ContentType): String {
        // Only normalize WEB content for grouping
        if (contentType != ContentType.WEB) return title
        
        // Remove common chapter markers and trailing content
        val patterns = listOf(
            Regex("""[–—\-:]?\s*(?:chapter|ch|ch\.)\s*\d+.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[–—\-]\s*\d+.*$"""), // "Title - 123" or "Title – 123"
            Regex("""\s*:\s*\d+.*$""") // "Title: 123"
        )
        var normalized = title
        for (pattern in patterns) {
            normalized = normalized.replace(pattern, "").trim()
        }
        return if (normalized.isBlank() || normalized.length < 3) title else normalized
    }
    
    /**
     * Try to extract chapter number from chapter label
     */
    private fun extractChapterNumber(chapterLabel: String?): Int? {
        if (chapterLabel == null) return null
        val regex = Regex("""\d+""")
        val match = regex.find(chapterLabel)
        return match?.value?.toIntOrNull()
    }
    
    /**
     * Toggle UI controls visibility
     */
    fun toggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }
    
    /**
     * Hide UI controls
     */
    fun hideControls() {
        _uiState.update { it.copy(showControls = false) }
    }

    /**
     * Toggle reading mode (Vertical Scroll vs Paged)
     */
    fun toggleReadingMode() {
        val newMode = !uiState.value.isPagedMode
        _uiState.update { it.copy(isPagedMode = newMode) }
        
        // Save preference to library
        currentLibraryItemId?.let { id ->
            viewModelScope.launch {
                libraryRepository.updateReadingMode(
                    id, 
                    if (newMode) io.aatricks.novelscraper.data.model.ReadingMode.PAGED 
                    else io.aatricks.novelscraper.data.model.ReadingMode.VERTICAL
                )
            }
        }
    }

    /**
     * Toggle RTL direction
     */
    fun toggleRtl() {
        _uiState.update { it.copy(isRtl = !it.isRtl) }
    }

    /**
     * Guess if the content should be read in Paged mode (Manga) or Vertical mode (Novel/Manhwa)
     */
    private fun guessIsPaged(content: ChapterContent): Boolean {
        val imageCount = content.getImageCount()
        val textCount = content.getTextCount()
        
        // 1. If mostly text -> Vertical (Light Novel)
        if (textCount > imageCount * 2) return false
        
        // 2. If it's mostly images
        if (imageCount > 0) {
            // Heuristic: Manhwas (Vertical) often have a VERY high number of images (long strips split into many files)
            // or the images themselves are very long (hard to know here).
            // Mangas (Paged) typically have 15-50 pages.
            // If image count is in the "manga range" and text is low -> Paged
            if (imageCount in 5..60 && textCount < 10) return true
            
            // If image count is very high -> likely Manhwa (Vertical)
            if (imageCount > 60) return false
        }
        
        return false
    }

    /**
     * Navigate to a specific chapter (e.g. from the chapter list)
     * Automatically adds to library and inherits metadata
     */
    fun navigateToChapter(url: String, title: String) {
        viewModelScope.launch {
            // Mark as explicit navigation to prevent scroll restoration
            isExplicitNavigation = true
            
            // Get current library item to extract metadata
            val currentItem = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
            
            // Check if selected chapter already exists in library
            val existingItem = libraryRepository.getItemByUrl(url)
            
            val itemId = if (existingItem != null) {
                // Chapter already exists, use its ID
                existingItem.id
            } else if (currentItem != null && currentItem.contentType == ContentType.WEB) {
                // Add new chapter to library with same metadata as current novel
                try {
                    val chapterLabel = extractChapterLabel(title) 
                        ?: extractChapterLabelFromUrl(url) 
                        ?: title
                    
                    val newItem = libraryRepository.addItem(
                        title = title.trim(),
                        url = url,
                        contentType = ContentType.WEB,
                        currentChapter = chapterLabel,
                        baseTitle = currentItem.baseTitle,
                        baseNovelUrl = currentItem.baseNovelUrl,
                        sourceName = currentItem.sourceName
                    )
                    // Inherit reading mode
                    libraryRepository.updateReadingMode(newItem.id, currentItem.readingMode)
                    newItem.id
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            
            // Load the selected chapter content
            loadContent(url, itemId)
        }
    }

    /**
     * Load full chapter list for the novel from the source
     */
    fun loadFullChapterList(baseUrl: String, sourceName: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isChaptersLoading = true) }
                
                val details = exploreRepository.getNovelDetails(baseUrl, sourceName)
                
                if (details != null && details.chapters.isNotEmpty()) {
                    _uiState.update { 
                        it.copy(
                            fullChapterList = details.chapters,
                            isChaptersLoading = false
                        ) 
                    }
                    
                    // Also update totalChapters in the library item
                    currentLibraryItemId?.let { id ->
                        val item = libraryRepository.getItemById(id)
                        if (item != null && item.totalChapters != details.chapters.size) {
                            libraryRepository.updateItem(item.copy(totalChapters = details.chapters.size))
                        }
                    }
                } else {
                    _uiState.update { it.copy(isChaptersLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isChaptersLoading = false) }
            }
        }
    }
}
