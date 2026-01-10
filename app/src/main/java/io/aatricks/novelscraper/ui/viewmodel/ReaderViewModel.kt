package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.util.TextUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * ViewModel for the reader screen.
 * Manages content loading, navigation, and reading progress.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    val contentRepository: ContentRepository,
    private val libraryRepository: LibraryRepository,
    private val exploreRepository: ExploreRepository,
    private val preferencesManager: PreferencesManager
) : BaseViewModel<ReaderViewModel.ReaderUiState>(ReaderUiState()) {

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
    // Job for tracking content loading
    private var loadJob: Job? = null
    
    init {
        // Load initial settings
        updateState {
            it.copy(
                fontSize = preferencesManager.fontSize,
                lineHeight = preferencesManager.lineHeight,
                fontFamily = preferencesManager.fontFamily,
                margins = preferencesManager.margins,
                paragraphSpacing = preferencesManager.paragraphSpacing,
                readerTheme = try {
                    ReaderTheme.valueOf(preferencesManager.readerTheme)
                } catch (e: Exception) {
                    ReaderTheme.DARK
                }
            )
        }

        // Load last read item
        viewModelScope.launch {
            val last = libraryRepository.getCurrentlyReading()
            last?.let { item ->
                val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                loadContent(loadUrl, item.id)
            }
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
        val fullChapterList: List<ChapterInfo> = emptyList(),
        val isChaptersLoading: Boolean = false,
        val seekTrigger: Long = 0L, // Timestamp to trigger seek in UI
        // Formatting Settings
        val fontSize: Float = 18f,
        val lineHeight: Float = 1.5f,
        val fontFamily: String = "Default",
        val margins: Int = 16,
        val paragraphSpacing: Float = 1.0f,
        val readerTheme: ReaderTheme = ReaderTheme.DARK
    )
    
    // Formatting update functions
    
    fun updateFontSize(newSize: Float) {
        val size = newSize.coerceIn(12f, 32f)
        preferencesManager.fontSize = size
        updateState { it.copy(fontSize = size) }
    }
    
    fun updateLineHeight(newHeight: Float) {
        val height = newHeight.coerceIn(1.0f, 2.5f)
        preferencesManager.lineHeight = height
        updateState { it.copy(lineHeight = height) }
    }
    
    fun updateFontFamily(newFamily: String) {
        preferencesManager.fontFamily = newFamily
        updateState { it.copy(fontFamily = newFamily) }
    }
    
    fun updateMargins(newMargins: Int) {
        val margins = newMargins.coerceIn(0, 64)
        preferencesManager.margins = margins
        updateState { it.copy(margins = margins) }
    }

    fun updateParagraphSpacing(newSpacing: Float) {
        val spacing = newSpacing.coerceIn(0.0f, 3.0f)
        preferencesManager.paragraphSpacing = spacing
        updateState { it.copy(paragraphSpacing = spacing) }
    }

    fun updateReaderTheme(newTheme: ReaderTheme) {
        preferencesManager.readerTheme = newTheme.name
        updateState { it.copy(readerTheme = newTheme) }
    }

    fun clearToast() {
        updateState { it.copy(toastMessage = null) }
    }

    fun loadContent(url: String, libraryItemId: String? = null, fromBottom: Boolean = false, isSilent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                if (url.contains("#")) {
                    val parts = url.split("#", limit = 2)
                    if (parts.size == 2) {
                        val basePath = parts[0]
                        val href = parts[1]
                        if (basePath.startsWith("content://") || basePath.endsWith(".epub", ignoreCase = true) || basePath.contains("epub")) {
                            loadEpubChapter(basePath, href, libraryItemId, fromBottom, isSilent)
                            return@launch
                        }
                    }
                }
                
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                if (prevItemId != null && prevContent != null) {
                    try {
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = "", 
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition,
                            lastReadIndex = _uiState.value.scrollIndex,
                            lastReadOffset = _uiState.value.scrollOffset
                        )
                    } catch (_: Exception) {}
                }

                if (!isSilent) {
                    updateState { it.copy(isLoading = true, error = null) }
                } else {
                    updateState { it.copy(error = null) }
                }

                when (val result = contentRepository.loadContent(url)) {
                    is ContentRepository.ContentResult.Success -> {
                        currentLibraryItemId = libraryItemId
                        val content = ChapterContent(
                            paragraphs = result.elements,
                            title = result.title,
                            url = result.url,
                            nextChapterUrl = contentRepository.incrementChapterUrl(result.url),
                            previousChapterUrl = contentRepository.decrementChapterUrl(result.url)
                        )

                        val libraryItem = libraryItemId?.let { libraryRepository.getItemById(it) }
                        val baseTitle = libraryItem?.baseTitle?.ifBlank { null }
                            ?: (libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) })
                            ?: (content.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) })
                            ?: ""
                        
                        val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
                        val chapterTitle = TextUtils.cleanChapterTitle(content.title, novelName).ifBlank {
                            libraryItem?.currentChapter ?: ""
                        }
                        
                        val baseNovelUrl = libraryItem?.baseNovelUrl ?: ""
                        val sourceName = libraryItem?.sourceName ?: ""
                        
                        val savedMode = libraryItem?.readingMode
                        val isPaged = if (savedMode != null) {
                            savedMode == ReadingMode.PAGED
                        } else {
                            TextUtils.guessIsPaged(content)
                        }

                        val initialIndex = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0
                        val initialPosition = if (fromBottom) 100f else 0f
                        val initialProgress = if (fromBottom) 100 else 0

                        updateState {
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
                                fullChapterList = emptyList()
                            )
                        }
                        
                        updateNavigationUrls()

                        libraryItem?.let { item ->
                            if (item.baseNovelUrl.isNotBlank() && item.sourceName.isNotBlank()) {
                                loadFullChapterList(item.baseNovelUrl, item.sourceName)
                            }
                        }

                        libraryItemId?.let {
                            libraryRepository.markAsCurrentlyReading(it)
                            if (!isExplicitNavigation) {
                                val item = libraryRepository.getItemById(it)
                                item?.let { libItem ->
                                    restoredScrollPercent = libItem.lastScrollPosition
                                    suppressAutoNavUntilUserInteraction = true
                                    updateState { state ->
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
                        
                        isExplicitNavigation = false
                    }
                    is ContentRepository.ContentResult.Error -> {
                        updateState {
                            it.copy(
                                isLoading = false,
                                isNavigating = false,
                                error = result.message
                            )
                        }
                        isExplicitNavigation = false
                    }
                }
            } catch (e: Exception) {
                updateState {
                    it.copy(
                        isLoading = false,
                        isNavigating = false,
                        error = "Failed to load content: ${e.message}"
                    )
                }
                isExplicitNavigation = false
            }
        }
    }

    fun navigateToNextChapter() {
        updateNavigationUrls()
        val nextUrl = _uiState.value.content?.nextChapterUrl ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = true
            val existingNextItem = libraryRepository.getItemByUrl(nextUrl)
            if (existingNextItem != null) {
                loadContent(nextUrl, existingNextItem.id)
                return@launch
            }

            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(nextUrl)
            updateState { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val nextItemId = addChapterToLibrary(nextUrl, result.title, isNext = true)
                    loadContent(nextUrl, nextItemId, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        updateState { it.copy(toastMessage = "Next chapter not found (404)") }
                    } else {
                        loadContent(nextUrl, isSilent = false)
                    }
                }
            }
        }
    }
    
    fun navigateToPreviousChapter(fromBottom: Boolean = false) {
        updateNavigationUrls()
        val prevUrl = _uiState.value.content?.previousChapterUrl ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = true
            val existingPrevItem = libraryRepository.getItemByUrl(prevUrl)
            if (existingPrevItem != null) {
                loadContent(prevUrl, existingPrevItem.id, fromBottom = fromBottom, isSilent = true)
                return@launch
            }

            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(prevUrl)
            updateState { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val prevItemId = addChapterToLibrary(prevUrl, result.title, isNext = false)
                    loadContent(prevUrl, prevItemId, fromBottom = fromBottom, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        updateState { it.copy(toastMessage = "Previous chapter not found (404)") }
                    } else {
                        loadContent(prevUrl, fromBottom = fromBottom, isSilent = false)
                    }
                }
            }
        }
    }

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
            libraryRepository.updateReadingMode(newItem.id, currentItem.readingMode)
            newItem.id
        } catch (e: Exception) {
            null
        }
    }
    
    fun loadEpubChapter(epubPath: String, href: String, libraryItemId: String? = null, fromBottom: Boolean = false, isSilent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val prevItemId = currentLibraryItemId
                val prevContent = _uiState.value.content
                if (prevItemId != null && prevContent != null) {
                    try {
                        libraryRepository.updateProgress(
                            itemId = prevItemId,
                            currentChapter = "", 
                            progress = _uiState.value.scrollProgress,
                            currentChapterUrl = prevContent.url,
                            lastScrollProgress = _uiState.value.scrollPosition,
                            lastReadIndex = _uiState.value.scrollIndex,
                            lastReadOffset = _uiState.value.scrollOffset
                        )
                    } catch (_: Exception) {}
                }

                if (!isSilent) {
                    updateState { it.copy(isLoading = true, error = null) }
                } else {
                    updateState { it.copy(error = null) }
                }

                val epubBook = contentRepository.getEpubBook(epubPath)
                if (epubBook == null) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            isNavigating = false,
                            error = "Failed to load EPUB structure"
                        )
                    }
                    return@launch
                }
                
                currentLibraryItemId = libraryItemId
                val chapter = contentRepository.loadEpubChapterFull(epubPath, href)
                if (chapter == null) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            isNavigating = false,
                            error = "Failed to load chapter content"
                        )
                    }
                    return@launch
                }
                
                val formattedElements = mutableListOf<ContentElement>()
                val textBuffer = mutableListOf<String>()

                fun flushTextBuffer() {
                    if (textBuffer.isEmpty()) return
                    val joined = textBuffer.joinToString("\n\n")
                    val formatted = TextUtils.formatChapterText(joined)
                    val parts = formatted.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
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

                val libraryItem = libraryItemId?.let { libraryRepository.getItemById(it) }
                val baseTitle = libraryItem?.baseTitle?.ifBlank { null } 
                    ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                    ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                    ?: ""
                
                val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
                val chapterTitle = TextUtils.cleanChapterTitle(content.title, novelName).ifBlank {
                    libraryItem?.currentChapter ?: ""
                }
                
                val initialIndex = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0
                val initialPosition = if (fromBottom) 100f else 0f
                val initialProgress = if (fromBottom) 100 else 0

                updateState {
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

                libraryItemId?.let {
                    libraryRepository.markAsCurrentlyReading(it)
                    if (!isExplicitNavigation) {
                        val item = libraryRepository.getItemById(it)
                        item?.let { libItem ->
                            restoredScrollPercent = libItem.lastScrollPosition
                            suppressAutoNavUntilUserInteraction = true
                            updateState { state ->
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
                
                isExplicitNavigation = false
            } catch (e: Exception) {
                updateState {
                    it.copy(
                        isLoading = false,
                        isNavigating = false,
                        error = "Failed to load EPUB chapter: ${e.message}"
                    )
                }
                isExplicitNavigation = false
            }
        }
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int
    ) {
        val deltaRaw = if (lastRawScrollOffset < 0f) 0f else scrollOffset - lastRawScrollOffset
        val isScrollingDown = deltaRaw > 0f

        val progress = if (maxScrollOffset > viewportHeight) {
            ((scrollOffset / (maxScrollOffset - viewportHeight)) * 100f).coerceIn(0f, 100f)
        } else if (maxScrollOffset > 0) {
            100f
        } else {
            0f
        }

        val hasReached = progress >= 25f
        val progressInt = progress.toInt()

        updateState {
            it.copy(
                scrollPosition = progress,
                scrollProgress = progressInt,
                scrollIndex = index,
                scrollOffset = offset,
                isScrollingDown = isScrollingDown,
                hasReachedQuarterScreen = hasReached
            )
        }

        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            delay(50) 
            if (progressInt > 0) {
                updateReadingProgress(progressInt)
            }
            if (suppressAutoNavUntilUserInteraction) {
                if (abs(progress - restoredScrollPercent) > 2f) {
                    suppressAutoNavUntilUserInteraction = false
                }
            }
            lastRawScrollOffset = scrollOffset
        }
    }

    fun updateReadingProgress(progress: Int) {
        try {
            currentLibraryItemId?.let { itemId ->
                val currentChapterUrl = _uiState.value.content?.url ?: ""
                val lastScroll = _uiState.value.scrollPosition
                val index = _uiState.value.scrollIndex
                val offset = _uiState.value.scrollOffset

                libraryRepository.saveProgress(
                    itemId = itemId,
                    currentChapter = "", 
                    progress = progress,
                    currentChapterUrl = currentChapterUrl,
                    lastScrollProgress = lastScroll,
                    lastReadIndex = index,
                    lastReadOffset = offset
                )
            }
        } catch (e: Exception) {}
    }

    fun clearError() {
        updateState { it.copy(error = null) }
    }

    fun retryLoad() {
        val url = _uiState.value.content?.url
        if (url != null) {
            loadContent(url, currentLibraryItemId)
        }
    }

    fun resetState() {
        _uiState.value = ReaderUiState()
        currentLibraryItemId = null
    }

    fun isContentCached(url: String): Boolean {
        return contentRepository.isCached(url)
    }

    fun clearCache(url: String) {
        viewModelScope.launch {
            try {
                contentRepository.clearCache(url)
            } catch (e: Exception) {}
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            try {
                contentRepository.clearAllCache()
            } catch (e: Exception) {
                updateState {
                    it.copy(error = "Failed to clear cache: ${e.message}")
                }
            }
        }
    }

    suspend fun getCacheSize(): Long {
        return contentRepository.getCacheSize()
    }

    fun saveScrollPosition(position: Float) {
        updateState { it.copy(scrollPosition = position) }
    }

    fun getScrollPosition(): Float {
        return _uiState.value.scrollPosition
    }

    fun seekToProgress(progress: Int) {
        val targetPercent = progress.coerceIn(0, 100).toFloat()
        val totalItems = _uiState.value.content?.paragraphs?.size ?: 0
        val roughIndex = if (totalItems > 0) {
            ((targetPercent / 100f) * totalItems).toInt().coerceIn(0, totalItems - 1)
        } else 0
        updateState { it.copy(
            scrollPosition = targetPercent, 
            scrollProgress = progress,
            scrollIndex = roughIndex,
            scrollOffset = 0,
            seekTrigger = System.currentTimeMillis()
        ) }
        updateReadingProgress(progress)
    }

    override fun onCleared() {
        super.onCleared()
        val progress = _uiState.value.scrollProgress
        if (progress > 0) {
            updateReadingProgress(progress)
        }
    }
    
    fun toggleControls() {
        updateState { it.copy(showControls = !it.showControls) }
    }
    
    fun hideControls() {
        updateState { it.copy(showControls = false) }
    }

    fun toggleReadingMode() {
        val newMode = !uiState.value.isPagedMode
        updateState { it.copy(isPagedMode = newMode) }
        currentLibraryItemId?.let { id ->
            viewModelScope.launch {
                libraryRepository.updateReadingMode(
                    id, 
                    if (newMode) ReadingMode.PAGED 
                    else ReadingMode.VERTICAL
                )
            }
        }
    }

    fun toggleRtl() {
        updateState { it.copy(isRtl = !it.isRtl) }
    }

    fun navigateToChapter(url: String, title: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = true
            val existingItem = libraryRepository.getItemByUrl(url)
            if (existingItem != null) {
                loadContent(url, existingItem.id)
                return@launch
            }
            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(url)
            updateState { it.copy(isNavigating = false) }
            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val itemId = addChapterToLibrary(url, result.title, isNext = true)
                    loadContent(url, itemId, isSilent = true)
                }
                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        updateState { it.copy(toastMessage = "Chapter not found (404)") }
                    } else {
                        loadContent(url, isSilent = false)
                    }
                }
            }
        }
    }

    fun loadFullChapterList(baseUrl: String, sourceName: String) {
        viewModelScope.launch {
            try {
                updateState { it.copy(isChaptersLoading = true) }
                val details = exploreRepository.getNovelDetails(baseUrl, sourceName)
                if (details != null && details.chapters.isNotEmpty()) {
                    updateState { 
                        it.copy(
                            fullChapterList = details.chapters,
                            isChaptersLoading = false
                        ) 
                    }
                    updateNavigationUrls()
                    currentLibraryItemId?.let { id ->
                        val item = libraryRepository.getItemById(id)
                        if (item != null && item.totalChapters != details.chapters.size) {
                            libraryRepository.updateItem(item.copy(totalChapters = details.chapters.size))
                        }
                    }
                } else {
                    updateState { it.copy(isChaptersLoading = false) }
                }
            }
            catch (e: Exception) {
                updateState { it.copy(isChaptersLoading = false) }
            }
        }
    }

    private fun updateNavigationUrls() {
        val state = _uiState.value
        val currentUrl = state.content?.url ?: return
        val list = state.fullChapterList
        if (list.isEmpty()) return

        val currentIndex = list.indexOfFirst { it.url == currentUrl }
        if (currentIndex != -1) {
            val prevUrl = if (currentIndex > 0) list[currentIndex - 1].url else null
            val nextUrl = if (currentIndex < list.size - 1) list[currentIndex + 1].url else null

            updateState { s ->
                s.copy(
                    content = s.content?.copy(
                        nextChapterUrl = nextUrl ?: s.content.nextChapterUrl,
                        previousChapterUrl = prevUrl ?: s.content.previousChapterUrl
                    ),
                    canNavigateNext = nextUrl != null || (s.content?.hasNextChapter() == true),
                    canNavigatePrevious = prevUrl != null || (s.content?.hasPreviousChapter() == true)
                )
            }
        }
    }
}