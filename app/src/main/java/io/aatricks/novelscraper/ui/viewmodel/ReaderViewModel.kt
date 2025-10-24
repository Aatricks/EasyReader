package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ChapterContent
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.repository.ContentRepository
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
    private val contentRepository: ContentRepository,
    private val libraryRepository: LibraryRepository
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
        val isScrollingDown: Boolean = true,
        val hasReachedQuarterScreen: Boolean = false,
        val canNavigateNext: Boolean = false,
        val canNavigatePrevious: Boolean = false
    )

    /**
     * Load content from URL or file path
     * @param url The URL or file path to load content from
     * @param libraryItemId Optional library item ID to track reading progress
     */
    fun loadContent(url: String, libraryItemId: String? = null) {
        viewModelScope.launch {
            try {
                // Save previous progress for the current library item before loading next
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                val prevProgress = _uiState.value.scrollProgress
                if (prevItemId != null && prevContent != null) {
                    try {
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = prevContent.title ?: prevContent.url.substringAfterLast('/'),
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition.toInt()
                        )
                    } catch (_: Exception) {}
                }

                _uiState.update { it.copy(isLoading = true, error = null) }
                currentLibraryItemId = libraryItemId

                when (val result = contentRepository.loadContent(url)) {
                    is ContentRepository.ContentResult.Success -> {
                        val content = ChapterContent(
                            paragraphs = result.paragraphs.map { ContentElement.Text(it) },
                            title = result.title,
                            url = result.url,
                            nextChapterUrl = contentRepository.incrementChapterUrl(result.url),
                            previousChapterUrl = contentRepository.decrementChapterUrl(result.url)
                        )

                        _uiState.update {
                            it.copy(
                                content = content,
                                isLoading = false,
                                error = null,
                                canNavigateNext = content.hasNextChapter(),
                                canNavigatePrevious = content.hasPreviousChapter(),
                                scrollPosition = 0f,
                                scrollProgress = 0,
                                hasReachedQuarterScreen = false
                            )
                        }

                        // Mark as currently reading in library and restore saved progress if available
                        libraryItemId?.let {
                            libraryRepository.markAsCurrentlyReading(it)
                            val item = libraryRepository.getItemById(it)
                            item?.let { libItem ->
                                // Restore last known saved chapter percent/position for this library item
                                restoredScrollPercent = libItem.lastScrollPosition.toFloat()
                                suppressAutoNavUntilUserInteraction = true
                                _uiState.update { state ->
                                    state.copy(
                                        scrollPosition = restoredScrollPercent,
                                        scrollProgress = libItem.progress
                                    )
                                }
                            }
                        }
                    }
                    is ContentRepository.ContentResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = result.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load content: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Navigate to the next chapter
     */
    fun navigateToNextChapter() {
        val nextUrl = _uiState.value.content?.nextChapterUrl
        if (nextUrl != null) {
            loadContent(nextUrl, currentLibraryItemId)
        }
    }

    /**
     * Navigate to the previous chapter
     */
    fun navigateToPreviousChapter() {
        val prevUrl = _uiState.value.content?.previousChapterUrl
        if (prevUrl != null) {
            loadContent(prevUrl, currentLibraryItemId)
        }
    }

    /**
     * Update scroll position and calculate progress
     * @param scrollOffset Current scroll offset
     * @param maxScrollOffset Maximum possible scroll offset
     * @param viewportHeight Height of the visible viewport
     */
    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float
    ) {
        // Cancel previous update and schedule new one with debounce
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            delay(100) // 100ms debounce to reduce jitter
            performScrollUpdate(scrollOffset, maxScrollOffset, viewportHeight)
        }
    }

    private fun performScrollUpdate(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float
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

        // If user reached bottom (progress >= 95%) and scrolling down, try to auto-navigate to next chapter
        if (isScrollingDown && progressInt >= 95 && now - lastAutoNavigateAt > 3000) {
            lastAutoNavigateAt = now
            val content = _uiState.value.content
            if (content != null && content.paragraphs.isNotEmpty() && content.hasNextChapter()) {
                navigateToNextChapter()
            }
        }

        // Previous-chapter auto-navigation disabled: previous chapters open only via explicit user actions

        // Update last raw scroll offset for next direction calculation
        lastRawScrollOffset = scrollOffset
    }

    /**
     * Update reading progress in the library
     * @param progress Progress percentage (0-100)
     */
    fun updateReadingProgress(progress: Int) {
        viewModelScope.launch {
            try {
                currentLibraryItemId?.let { itemId ->
                    val contentUrl = _uiState.value.content?.url
                    val currentChapter = extractChapterLabelFromUrl(contentUrl ?: "") ?: _uiState.value.content?.title
                        ?: _uiState.value.content?.url?.substringAfterLast("/")
                        ?: "Unknown Chapter"
                    val currentChapterUrl = _uiState.value.content?.url ?: ""
                    val lastScroll = _uiState.value.scrollPosition.toInt()

                    libraryRepository.updateProgress(
                        itemId = itemId,
                        currentChapter = currentChapter,
                        progress = progress,
                        currentChapterUrl = currentChapterUrl,
                        lastScrollProgress = lastScroll
                    )
                }
            } catch (e: Exception) {
                // Silently fail progress updates to not interrupt reading
            }
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

    override fun onCleared() {
        super.onCleared()
        // Save final reading progress before clearing
        val progress = _uiState.value.scrollProgress
        if (progress > 0) {
            updateReadingProgress(progress)
        }
    }
}
