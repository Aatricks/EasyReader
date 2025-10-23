package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ChapterContent
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

                        // Mark as currently reading in library
                        libraryItemId?.let {
                            libraryRepository.markAsCurrentlyReading(it)
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
        val quarterScreenThreshold = viewportHeight / 4f
        val hasReached = scrollOffset >= quarterScreenThreshold

        // Determine scroll direction
        val isScrollingDown = scrollOffset > _uiState.value.scrollPosition

        // Calculate progress percentage
        val progress = if (maxScrollOffset > 0) {
            ((scrollOffset / maxScrollOffset) * 100).coerceIn(0f, 100f).toInt()
        } else {
            0
        }

        _uiState.update {
            it.copy(
                scrollPosition = scrollOffset,
                scrollProgress = progress,
                isScrollingDown = isScrollingDown,
                hasReachedQuarterScreen = hasReached
            )
        }

        // Update reading progress in library when reaching milestones
        if (progress > 0 && progress % 10 == 0) {
            updateReadingProgress(progress)
        }
    }

    /**
     * Update reading progress in the library
     * @param progress Progress percentage (0-100)
     */
    fun updateReadingProgress(progress: Int) {
        viewModelScope.launch {
            try {
                currentLibraryItemId?.let { itemId ->
                    val currentChapter = _uiState.value.content?.title
                        ?: _uiState.value.content?.url?.substringAfterLast("/")
                        ?: "Unknown Chapter"
                    
                    libraryRepository.updateProgress(
                        itemId = itemId,
                        currentChapter = currentChapter,
                        progress = progress
                    )
                }
            } catch (e: Exception) {
                // Silently fail progress updates to not interrupt reading
            }
        }
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
