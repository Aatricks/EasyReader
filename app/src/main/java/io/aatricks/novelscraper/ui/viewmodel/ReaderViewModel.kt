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
    private val exploreRepository: io.aatricks.novelscraper.data.repository.ExploreRepository,
    private val preferencesManager: io.aatricks.novelscraper.data.local.PreferencesManager
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
    
    init {
        // Load initial settings
        _uiState.update {
            it.copy(
                fontSize = preferencesManager.fontSize,
                lineHeight = preferencesManager.lineHeight,
                fontFamily = preferencesManager.fontFamily,
                margins = preferencesManager.margins,
                paragraphSpacing = preferencesManager.paragraphSpacing
            )
        }
    }

    /**
     * Data class representing the reader UI state
     */
    data class ReaderUiState(
        val content: ChapterContent? = null,
        val isLoading: Boolean = false,
        val isNavigating: Boolean = false, // Loading next/prev chapter in background
        val error: String? = null,
        val toastMessage: String? = null, // Temporary message to show (Toast/Snackbar)
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
        val seekTrigger: Long = 0L, // Timestamp to trigger seek in UI
        // Formatting Settings
        val fontSize: Float = 18f,
        val lineHeight: Float = 1.5f,
        val fontFamily: String = "Default",
        val margins: Int = 16,
        val paragraphSpacing: Float = 1.0f
    )
    
    // Formatting update functions
    
    fun updateFontSize(newSize: Float) {
        val size = newSize.coerceIn(12f, 32f)
        preferencesManager.fontSize = size
        _uiState.update { it.copy(fontSize = size) }
    }
    
    fun updateLineHeight(newHeight: Float) {
        val height = newHeight.coerceIn(1.0f, 2.5f)
        preferencesManager.lineHeight = height
        _uiState.update { it.copy(lineHeight = height) }
    }
    
    fun updateFontFamily(newFamily: String) {
        preferencesManager.fontFamily = newFamily
        _uiState.update { it.copy(fontFamily = newFamily) }
    }
    
    fun updateMargins(newMargins: Int) {
        val margins = newMargins.coerceIn(0, 64)
        preferencesManager.margins = margins
        _uiState.update { it.copy(margins = margins) }
    }

    fun updateParagraphSpacing(newSpacing: Float) {
        val spacing = newSpacing.coerceIn(0.0f, 3.0f)
        preferencesManager.paragraphSpacing = spacing
        _uiState.update { it.copy(paragraphSpacing = spacing) }
    }

    /**
     * Clear the current toast message
     */
    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /**
     * Load content from URL or file path
     * @param url The URL or file path to load content from
     * @param libraryItemId Optional library item ID to track reading progress
     * @param fromBottom If true, initialize scroll position at the end of the content
     * @param isSilent If true, don't show full-screen loading state (keep current content)
     */
    fun loadContent(url: String, libraryItemId: String? = null, fromBottom: Boolean = false, isSilent: Boolean = false) {
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
                            loadEpubChapter(basePath, href, libraryItemId, fromBottom, isSilent)
                            return@launch
                        }
                    }
                }
                
                // Save previous progress for the current library item before loading next
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                if (prevItemId != null && prevContent != null) {
                    try {
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = "", 
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition.toInt(),
                            lastReadIndex = _uiState.value.scrollIndex,
                            lastReadOffset = _uiState.value.scrollOffset
                        )
                    } catch (_: Exception) {}
                }

                if (!isSilent) {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                } else {
                    _uiState.update { it.copy(error = null) }
                }
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
                        
                        // Use library baseTitle if available, otherwise extract it from content title
                        val baseTitle = libraryItem?.baseTitle?.ifBlank { null }
                            ?: (libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) })
                            ?: (content.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) })
                            ?: ""
                        
                        val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
                        
                        // Clean chapter title by removing the novel name from it to avoid duplication
                        val chapterTitle = cleanChapterTitle(content.title, novelName).ifBlank {
                            libraryItem?.currentChapter ?: ""
                        }
                        
                        val baseNovelUrl = libraryItem?.baseNovelUrl ?: ""
                        val sourceName = libraryItem?.sourceName ?: ""
                        
                        // Determine reading mode
                        val savedMode = libraryItem?.readingMode
                        val isPaged = if (savedMode != null) {
                            savedMode == io.aatricks.novelscraper.data.model.ReadingMode.PAGED
                        } else {
                            guessIsPaged(content)
                        }

                        val initialIndex = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0
                        val initialPosition = if (fromBottom) 100f else 0f
                        val initialProgress = if (fromBottom) 100 else 0

                        _uiState.update {
                            it.copy(
                                content = content,
                                isLoading = false,
                                isNavigating = false,
                                error = null,
                                canNavigateNext = content.hasNextChapter(),
                                canNavigatePrevious = content.hasPreviousChapter(),
                                scrollPosition = initialPosition,
                                scrollProgress = initialProgress,
                                scrollIndex = initialIndex,
                                scrollOffset = 0,
                                hasReachedQuarterScreen = fromBottom,
                                novelName = novelName,
                                chapterTitle = chapterTitle,
                                baseTitle = baseTitle,
                                baseNovelUrl = baseNovelUrl,
                                sourceName = sourceName,
                                isPagedMode = isPaged,
                                fullChapterList = emptyList() // Reset chapter list when loading new content
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
                                isNavigating = false,
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
                        isNavigating = false,
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
        val nextUrl = _uiState.value.content?.nextChapterUrl ?: return
        viewModelScope.launch {
            // Mark as explicit navigation to prevent scroll restoration
            isExplicitNavigation = true
            
            // Check if next chapter already exists in library
            val existingNextItem = libraryRepository.getItemByUrl(nextUrl)
            if (existingNextItem != null) {
                loadContent(nextUrl, existingNextItem.id)
                return@launch
            }

            // Not in library, check if it's 404 before adding
            _uiState.update { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(nextUrl)
            _uiState.update { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val nextItemId = addChapterToLibrary(nextUrl, result.title, isNext = true)
                    loadContent(nextUrl, nextItemId, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        _uiState.update { it.copy(toastMessage = "Next chapter not found (404)") }
                    } else {
                        // For other errors (403, etc.), proceed to show error state/Cloudflare webview
                        loadContent(nextUrl, isSilent = false)
                    }
                }
            }
        }
    }
    
    /**
     * Navigate to the previous chapter
     * Automatically adds the chapter to library if it doesn't exist
     * @param fromBottom If true, initialize scroll position at the end of the content
     */
    fun navigateToPreviousChapter(fromBottom: Boolean = false) {
        val prevUrl = _uiState.value.content?.previousChapterUrl ?: return
        viewModelScope.launch {
            // Mark as explicit navigation to prevent scroll restoration
            isExplicitNavigation = true
            
            // Check if previous chapter already exists in library
            val existingPrevItem = libraryRepository.getItemByUrl(prevUrl)
            if (existingPrevItem != null) {
                loadContent(prevUrl, existingPrevItem.id, fromBottom = fromBottom, isSilent = true)
                return@launch
            }

            // Not in library, check if it's 404 before adding
            _uiState.update { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(prevUrl)
            _uiState.update { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val prevItemId = addChapterToLibrary(prevUrl, result.title, isNext = false)
                    loadContent(prevUrl, prevItemId, fromBottom = fromBottom, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        _uiState.update { it.copy(toastMessage = "Previous chapter not found (404)") }
                    } else {
                        // For other errors (403, etc.), proceed to show error state/Cloudflare webview
                        loadContent(prevUrl, fromBottom = fromBottom, isSilent = false)
                    }
                }
            }
        }
    }

    /**
     * Helper to add a chapter to library inheriting metadata from current chapter
     */
    private suspend fun addChapterToLibrary(url: String, fetchedTitle: String?, isNext: Boolean): String? {
        val currentItem = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
        if (currentItem == null || currentItem.contentType != ContentType.WEB) return null

        return try {
            val title = fetchedTitle ?: url
            val chapterLabel = TextUtils.extractChapterLabel(title) 
                ?: TextUtils.extractChapterLabelFromUrl(url) 
                ?: (if (isNext) "Next Chapter" else "Previous Chapter")
            
            val baseTitle = if (currentItem.baseTitle.isNotBlank()) {
                currentItem.baseTitle
            } else {
                TextUtils.extractBaseTitle(currentItem.title, ContentType.WEB)
            }
            
            val newItem = libraryRepository.addItem(
                title = title.trim().ifBlank { "$baseTitle - $chapterLabel" },
                url = url,
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
            null
        }
    }
    
    /**
     * Load a specific EPUB chapter by href
     * @param epubPath The path to the EPUB file
     * @param href The chapter href within the EPUB
     * @param libraryItemId Optional library item ID to track reading progress
     * @param fromBottom If true, initialize scroll position at the end of the content
     * @param isSilent If true, don't show full-screen loading state
     */
    fun loadEpubChapter(epubPath: String, href: String, libraryItemId: String? = null, fromBottom: Boolean = false, isSilent: Boolean = false) {
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

                if (!isSilent) {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                } else {
                    _uiState.update { it.copy(error = null) }
                }
                currentLibraryItemId = libraryItemId

                // Get EPUB book structure
                val epubBook = contentRepository.getEpubBook(epubPath)
                if (epubBook == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNavigating = false,
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
                            isNavigating = false,
                            error = "Failed to load chapter content"
                        )
                    }
                    return@launch
                }
                
                // ...
                val formattedElements = mutableListOf<ContentElement>()
                // ...
                // I'll use a larger block to avoid missing code
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
                        is ContentElement.ImageGroup -> {
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
                val baseTitle = libraryItem?.baseTitle?.ifBlank { null }
                    ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                    ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                    ?: ""
                
                val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
                val chapterTitle = cleanChapterTitle(content.title, novelName).ifBlank {
                    libraryItem?.currentChapter ?: ""
                }
                
                val initialIndex = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0
                val initialPosition = if (fromBottom) 100f else 0f
                val initialProgress = if (fromBottom) 100 else 0

                _uiState.update {
                    it.copy(
                        content = content,
                        isLoading = false,
                        isNavigating = false,
                        error = null,
                        canNavigateNext = content.hasNextChapter(),
                        canNavigatePrevious = content.hasPreviousChapter(),
                        scrollPosition = initialPosition,
                        scrollProgress = initialProgress,
                        scrollIndex = initialIndex,
                        scrollOffset = 0,
                        hasReachedQuarterScreen = fromBottom,
                        novelName = novelName,
                        chapterTitle = chapterTitle,
                        baseTitle = baseTitle
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
                        isNavigating = false,
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
     * Clear error state
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clean chapter title by removing the novel name, common web junk, and separators.
     * If the title still looks like a web page title, attempts to extract just the chapter label.
     */
    private fun cleanChapterTitle(fullTitle: String?, novelName: String): String {
        if (fullTitle == null || fullTitle.isBlank()) return ""
        
        var cleaned: String = fullTitle
        
        // 1. Remove common web novel "junk" first
        val junkPatterns = listOf(
            Regex("""(?i)^read\s+"""),
            Regex("""(?i)\s+free\s+online.*$"""),
            Regex("""(?i)\s+online\s+free.*$"""),
            Regex("""(?i)\s*\|\s*.*$"""), // Remove anything after |
            Regex("""(?i)\s+at\s+.*$"""), // Remove " at SourceName"
            Regex("""(?i)[\s–—\-:]*(MangaBat|NovelFire|MangaPark|MangaKakalot).*$"""),
            Regex("""(?i)[\s–—\-:]*Scan.*$""")
        )
        
        for (pattern in junkPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }

        if (novelName.isNotBlank()) {
            // Remove novel name if it's present
            if (cleaned.contains(novelName, ignoreCase = true)) {
                cleaned = cleaned.replace(novelName, "", ignoreCase = true)
            }
        }
        
        // Remove leading/trailing separators
        cleaned = cleaned.replace(Regex("""^[\s–—\-:\|]+"""), "")
            .replace(Regex("""[\s–—\-:\|]+$"""), "")
            .trim()
            
        // 2. If it still looks like a long web page title or contains "Chapter", 
        // try to extract just the chapter label + subtitle
        if (cleaned.length > 40 || cleaned.contains("Chapter", ignoreCase = true) || cleaned.contains("Ch.", ignoreCase = true)) {
             val extractedLabel = TextUtils.extractChapterLabel(cleaned)
             if (extractedLabel != null) {
                 // Check if there is a subtitle after the chapter label
                 // e.g. "Chapter 233 - The Final Battle"
                 val subTitleRegex = Regex("""(?i)(?:chapter|ch|ch\.)\s*\d+[\s:\-—–\|]+(.+)""")
                 val match = subTitleRegex.find(cleaned)
                 val subTitle = match?.groupValues?.get(1)?.trim()
                 
                 return if (!subTitle.isNullOrBlank() && subTitle.length > 2) {
                     "$extractedLabel: $subTitle"
                 } else {
                     extractedLabel
                 }
             }
        }

        // If everything was removed or it's just the novel name, return empty
        if (cleaned.isBlank() || (novelName.isNotBlank() && fullTitle.equals(novelName, ignoreCase = true))) {
            return ""
        }
        
        return cleaned
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
            
            // Check if selected chapter already exists in library
            val existingItem = libraryRepository.getItemByUrl(url)
            if (existingItem != null) {
                loadContent(url, existingItem.id)
                return@launch
            }

            // Not in library, check if it's 404 before adding
            _uiState.update { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(url)
            _uiState.update { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val itemId = addChapterToLibrary(url, result.title, isNext = true)
                    loadContent(url, itemId, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        _uiState.update { it.copy(toastMessage = "Chapter not found (404)") }
                    } else {
                        loadContent(url, isSilent = false)
                    }
                }
            }
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
