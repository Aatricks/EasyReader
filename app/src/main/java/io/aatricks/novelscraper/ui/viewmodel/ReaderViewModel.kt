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
import io.aatricks.novelscraper.util.UrlSecurity
import kotlinx.coroutines.Dispatchers
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
                readerTheme = runCatching { ReaderTheme.valueOf(preferencesManager.readerTheme) }.getOrDefault(
                    ReaderTheme.DARK
                )
            )
        }

        // Load last read item
        viewModelScope.launch {
            libraryRepository.getCurrentlyReading()?.let { last ->
                val loadUrl = last.currentChapterUrl.ifBlank { last.url }
                loadContent(loadUrl, last.id)
            } ?: updateState { it.copy(isLoading = false) }
        }
    }

    /**
     * Data class representing the reader UI state
     */
    data class ReaderUiState(
        val content: ChapterContent? = null,
        val isLoading: Boolean = true,
        val isNavigating: Boolean = false,
        val error: String? = null,
        val lastAttemptedUrl: String? = null,
        val toastMessage: String? = null,
        val scrollPosition: Float = 0f,
        val scrollProgress: Int = 0,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val isScrollingDown: Boolean = true,
        val hasReachedQuarterScreen: Boolean = false,
        val canNavigateNext: Boolean = false,
        val canNavigatePrevious: Boolean = false,
        val showControls: Boolean = false,
        val novelName: String = "",
        val chapterTitle: String = "",
        val baseTitle: String = "",
        val baseNovelUrl: String = "",
        val sourceName: String = "",
        val isPagedMode: Boolean = false,
        val isRtl: Boolean = true,
        val fullChapterList: List<ChapterInfo> = emptyList(),
        val isChaptersLoading: Boolean = false,
        val seekTrigger: Long = 0L,
        val fontSize: Float = 18f,
        val lineHeight: Float = 1.5f,
        val fontFamily: String = "Default",
        val margins: Int = 16,
        val paragraphSpacing: Float = 1.0f,
        val readerTheme: ReaderTheme = ReaderTheme.DARK,
        val pendingExternalUrl: String? = null,
        val showExternalUrlConfirmation: Boolean = false
    )

    fun requestOpenUrl(url: String): Unit {
        viewModelScope.launch {
            if (UrlSecurity.isSafeUrl(url)) {
                updateState { it.copy(pendingExternalUrl = url, showExternalUrlConfirmation = true) }
            } else {
                updateState { it.copy(toastMessage = "Blocked unsafe or invalid URL") }
            }
        }
    }

    fun confirmExternalUrl(): Unit {
        val url = _uiState.value.pendingExternalUrl ?: return
        updateState { it.copy(pendingExternalUrl = null, showExternalUrlConfirmation = false) }
        loadContent(url)
    }

    fun cancelExternalUrl(): Unit {
        updateState { it.copy(pendingExternalUrl = null, showExternalUrlConfirmation = false) }
    }

    fun updateFontSize(newSize: Float): Unit {
        val size = newSize.coerceIn(12f, 32f)
        preferencesManager.fontSize = size
        updateState { it.copy(fontSize = size) }
    }

    fun updateLineHeight(newHeight: Float): Unit {
        val height = newHeight.coerceIn(1.0f, 2.5f)
        preferencesManager.lineHeight = height
        updateState { it.copy(lineHeight = height) }
    }

    fun updateFontFamily(newFamily: String): Unit {
        preferencesManager.fontFamily = newFamily
        updateState { it.copy(fontFamily = newFamily) }
    }

    fun updateMargins(newMargins: Int): Unit {
        val margins = newMargins.coerceIn(0, 64)
        preferencesManager.margins = margins
        updateState { it.copy(margins = margins) }
    }

    fun updateParagraphSpacing(newSpacing: Float): Unit {
        val spacing = newSpacing.coerceIn(0.0f, 3.0f)
        preferencesManager.paragraphSpacing = spacing
        updateState { it.copy(paragraphSpacing = spacing) }
    }

    fun updateReaderTheme(newTheme: ReaderTheme): Unit {
        preferencesManager.readerTheme = newTheme.name
        updateState { it.copy(readerTheme = newTheme) }
    }

    fun clearToast(): Unit {
        updateState { it.copy(toastMessage = null) }
    }

    fun loadContent(
        url: String,
        libraryItemId: String? = null,
        fromBottom: Boolean = false,
        isSilent: Boolean = false
    ): Unit {
        loadJob?.cancel()
        progressUpdateJob?.cancel()
        loadJob = viewModelScope.launch {
            if (handleEpubUrl(url, libraryItemId, fromBottom, isSilent)) return@launch

            saveCurrentProgress()
            updateState {
                it.copy(
                    isLoading = !isSilent,
                    error = null,
                    lastAttemptedUrl = url,
                    content = if (isSilent) it.content else null
                )
            }

            when (val result = contentRepository.loadContent(url)) {
                is ContentRepository.ContentResult.Success -> {
                    updateState { it.copy(lastAttemptedUrl = null) }
                    handleLoadSuccess(result, libraryItemId, fromBottom)
                }

                is ContentRepository.ContentResult.Error -> handleLoadError(result)
            }
        }
    }

    private suspend fun handleEpubUrl(
        url: String,
        libraryItemId: String?,
        fromBottom: Boolean,
        isSilent: Boolean
    ): Boolean {
        val parts = url.split("#", limit = 2)
        if (parts.size != 2) return false

        val basePath = parts[0]
        val href = parts[1]
        val isEpub = basePath.startsWith("content://") ||
                basePath.lowercase().run { endsWith(".epub") || contains("epub") }

        return if (isEpub) {
            loadEpubChapter(basePath, href, libraryItemId, fromBottom, isSilent)
            true
        } else false
    }

    private suspend fun saveCurrentProgress(): Unit {
        val prevItemId = currentLibraryItemId ?: return
        val prevContent = _uiState.value.content ?: return

        runCatching {
            libraryRepository.updateProgress(
                itemId = prevItemId,
                currentChapter = "",
                progress = _uiState.value.scrollProgress,
                currentChapterUrl = prevContent.url,
                lastScrollProgress = _uiState.value.scrollPosition,
                lastReadIndex = _uiState.value.scrollIndex,
                lastReadOffset = _uiState.value.scrollOffset
            )
        }
    }

    private suspend fun handleLoadSuccess(
        result: ContentRepository.ContentResult.Success,
        libraryItemId: String?,
        fromBottom: Boolean
    ): Unit {
        val effectiveLibraryItemId = libraryItemId ?: libraryRepository.getItemByUrl(result.url)?.id
        currentLibraryItemId = effectiveLibraryItemId

        val content = ChapterContent(
            paragraphs = result.elements,
            title = result.title,
            url = result.url,
            nextChapterUrl = contentRepository.incrementChapterUrl(result.url),
            previousChapterUrl = contentRepository.decrementChapterUrl(result.url)
        )

        val libraryItem = effectiveLibraryItemId?.let { libraryRepository.getItemById(it) }
        val baseTitle = getBaseTitle(content, libraryItem)
        val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
        val chapterTitle = TextUtils.cleanChapterTitle(content.title, novelName).ifBlank {
            libraryItem?.currentChapter ?: ""
        }

        val isPaged =
            libraryItem?.readingMode == ReadingMode.PAGED || (libraryItem?.readingMode == null && TextUtils.guessIsPaged(
                content
            ))

        val initialScroll = calculateInitialScroll(content, libraryItem, fromBottom)

        updateState {
            it.copy(
                content = content,
                isLoading = false,
                isNavigating = false,
                error = null,
                canNavigateNext = content.hasNextChapter(),
                canNavigatePrevious = content.hasPreviousChapter(),
                scrollPosition = initialScroll.position,
                scrollProgress = initialScroll.progress,
                scrollIndex = initialScroll.index,
                scrollOffset = initialScroll.offset,
                hasReachedQuarterScreen = fromBottom || initialScroll.progress >= 25,
                novelName = novelName,
                chapterTitle = chapterTitle,
                baseTitle = baseTitle,
                baseNovelUrl = libraryItem?.baseNovelUrl ?: "",
                sourceName = libraryItem?.sourceName ?: "",
                isPagedMode = isPaged,
                fullChapterList = emptyList()
            )
        }

        updateNavigationUrls()

        libraryItem?.let { item ->
            if (item.baseNovelUrl.isNotBlank() && item.sourceName.isNotBlank()) {
                loadFullChapterList(item.baseNovelUrl, item.sourceName)
            }
            libraryRepository.markAsCurrentlyReading(item.id)
            performAutoDeletion(content.url, novelName, chapterTitle)
        }

        isExplicitNavigation = false
    }

    private fun performAutoDeletion(currentUrl: String, novelName: String, chapterTitle: String) {
        val baseTitle = _uiState.value.baseTitle.ifBlank { novelName }
        if (baseTitle.isBlank()) return

        viewModelScope.launch {
            delay(1000) // Ensure progress is saved if navigating from a finished chapter

            val allItems = libraryRepository.libraryItems.value
            val currentChapterNumber = TextUtils.extractChapterNumber(chapterTitle)
                ?: TextUtils.extractChapterNumber(currentUrl)
                ?: return@launch

            val toDelete = allItems.filter { item ->
                item.baseTitle == baseTitle &&
                        item.contentType == ContentType.WEB &&
                        item.url != currentUrl &&
                        item.progress == 100
            }.filter { item ->
                val otherNumber = TextUtils.extractChapterNumber(item.currentChapter)
                    ?: TextUtils.extractChapterNumber(item.url)
                    ?: return@filter false

                (currentChapterNumber - otherNumber) > 1
            }

            if (toDelete.isNotEmpty()) {
                val ids = toDelete.map { it.id }.toSet()
                libraryRepository.removeItems(ids)
                toDelete.forEach { item ->
                    contentRepository.clearCache(item.url)
                }
            }
        }
    }

    private fun handleLoadError(result: ContentRepository.ContentResult.Error): Unit {
        updateState { it.copy(isLoading = false, isNavigating = false, error = result.message) }
        isExplicitNavigation = false
    }

    private fun getBaseTitle(content: ChapterContent, libraryItem: LibraryItem?): String {
        return libraryItem?.baseTitle?.ifBlank { null }
            ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) }
            ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) }
            ?: ""
    }

    private data class ScrollState(
        val index: Int,
        val position: Float,
        val progress: Int,
        val offset: Int
    )

    private fun calculateInitialScroll(
        content: ChapterContent,
        libraryItem: LibraryItem?,
        fromBottom: Boolean
    ): ScrollState {
        return if (libraryItem != null && !isExplicitNavigation) {
            restoredScrollPercent = libraryItem.lastScrollPosition
            suppressAutoNavUntilUserInteraction = true
            ScrollState(
                index = libraryItem.lastReadIndex,
                position = libraryItem.lastScrollPosition,
                progress = libraryItem.progress,
                offset = libraryItem.lastReadOffset
            )
        } else {
            ScrollState(
                index = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0,
                position = if (fromBottom) 100f else 0f,
                progress = if (fromBottom) 100 else 0,
                offset = 0
            )
        }
    }

    fun navigateToNextChapter(): Unit = navigateToAdjacentChapter(isNext = true)
    fun navigateToPreviousChapter(fromBottom: Boolean = false): Unit =
        navigateToAdjacentChapter(isNext = false, fromBottom = fromBottom)

    private fun navigateToAdjacentChapter(isNext: Boolean, fromBottom: Boolean = false): Unit {
        updateNavigationUrls()
        val url = if (isNext) _uiState.value.content?.nextChapterUrl else _uiState.value.content?.previousChapterUrl
        if (url == null) return

        loadJob?.cancel()
        progressUpdateJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = true
            libraryRepository.getItemByUrl(url)?.let { existingItem ->
                loadContent(url, existingItem.id, fromBottom = fromBottom, isSilent = true)
                return@launch
            }

            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(url)
            updateState { it.copy(isNavigating = false) }

            when (result) {
                is ContentRepository.ContentResult.Success -> {
                    val itemId = addChapterToLibrary(url, result.title, isNext = isNext)
                    loadContent(url, itemId, fromBottom = fromBottom, isSilent = true)
                }

                is ContentRepository.ContentResult.Error -> {
                    if (result.message.contains("404")) {
                        val msg = if (isNext) "Next chapter not found (404)" else "Previous chapter not found (404)"
                        updateState { it.copy(toastMessage = msg) }
                    } else {
                        loadContent(url, fromBottom = fromBottom, isSilent = false)
                    }
                }
            }
        }
    }

    private suspend fun addChapterToLibrary(
        url: String,
        fetchedTitle: String?,
        isNext: Boolean
    ): String? {
        val currentItem = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
        if (currentItem == null || currentItem.contentType != ContentType.WEB) return null

        return runCatching {
            val title = fetchedTitle ?: url
            val chapterLabel = TextUtils.extractChapterLabel(title)
                ?: TextUtils.extractChapterLabelFromUrl(url)
                ?: (if (isNext) "Next Chapter" else "Previous Chapter")

            val baseTitle = currentItem.baseTitle.ifBlank {
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
        }.getOrNull()
    }

    fun loadEpubChapter(
        epubPath: String,
        href: String,
        libraryItemId: String? = null,
        fromBottom: Boolean = false,
        isSilent: Boolean = false
    ): Unit {
        loadJob?.cancel()
        progressUpdateJob?.cancel()
        loadJob = viewModelScope.launch {
            saveCurrentProgress()

            if (!isSilent) {
                updateState { it.copy(isLoading = true, error = null) }
            } else {
                updateState { it.copy(error = null) }
            }

            val epubBook = contentRepository.getEpubBook(epubPath)
            if (epubBook == null) {
                handleLoadError(ContentRepository.ContentResult.Error("Failed to load EPUB structure"))
                return@launch
            }

            val chapter = contentRepository.loadEpubChapterFull(epubPath, href)
            if (chapter == null) {
                handleLoadError(ContentRepository.ContentResult.Error("Failed to load chapter content"))
                return@launch
            }

            val effectiveLibraryItemId = libraryItemId ?: libraryRepository.getItemByUrl(epubPath)?.id
            currentLibraryItemId = effectiveLibraryItemId

            val content = ChapterContent(
                paragraphs = formatEpubElements(chapter.content),
                title = chapter.title,
                url = "$epubPath#$href",
                nextChapterUrl = chapter.nextHref?.let { "$epubPath#${it}" }
                    ?: epubBook.getNextHref(href)?.let { "$epubPath#${it}" },
                previousChapterUrl = chapter.previousHref?.let { "$epubPath#${it}" }
                    ?: epubBook.getPreviousHref(href)?.let { "$epubPath#${it}" }
            )

            val libraryItem = effectiveLibraryItemId?.let { libraryRepository.getItemById(it) }
            val baseTitle = libraryItem?.baseTitle?.ifBlank { null }
                ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                ?: ""

            val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
            val chapterTitle = TextUtils.cleanChapterTitle(content.title, novelName).ifBlank {
                libraryItem?.currentChapter ?: ""
            }

            val initialScroll = calculateInitialScroll(content, libraryItem, fromBottom)

            updateState {
                it.copy(
                    content = content,
                    isLoading = false,
                    isNavigating = false,
                    error = null,
                    canNavigateNext = content.hasNextChapter(),
                    canNavigatePrevious = content.hasPreviousChapter(),
                    scrollPosition = initialScroll.position,
                    scrollProgress = initialScroll.progress,
                    scrollIndex = initialScroll.index,
                    scrollOffset = initialScroll.offset,
                    hasReachedQuarterScreen = fromBottom || initialScroll.progress >= 25,
                    novelName = novelName,
                    chapterTitle = chapterTitle,
                    baseTitle = baseTitle
                )
            }

            effectiveLibraryItemId?.let {
                libraryRepository.markAsCurrentlyReading(it)
            }

            isExplicitNavigation = false
        }
    }

    private fun formatEpubElements(rawElements: List<ContentElement>): List<ContentElement> {
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

        for (el in rawElements) {
            when (el) {
                is ContentElement.Text -> textBuffer.add(el.content)
                is ContentElement.Image, is ContentElement.ImageGroup -> {
                    flushTextBuffer()
                    formattedElements.add(el)
                }
            }
        }
        flushTextBuffer()
        return formattedElements
    }

    fun onUserInteraction(): Unit {
        suppressAutoNavUntilUserInteraction = false
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int
    ): Unit {
        val deltaRaw = if (lastRawScrollOffset < 0f) 0f else scrollOffset - lastRawScrollOffset
        val isScrollingDown = deltaRaw > 0f

        val progress = when {
            maxScrollOffset > viewportHeight -> ((scrollOffset / (maxScrollOffset - viewportHeight)) * 100f).coerceIn(
                0f,
                100f
            )

            maxScrollOffset > 0 -> 100f
            else -> 0f
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
            delay(100)

            if (suppressAutoNavUntilUserInteraction) {
                if (abs(progress - restoredScrollPercent) < 1f) {
                    suppressAutoNavUntilUserInteraction = false
                } else {
                    lastRawScrollOffset = scrollOffset
                    return@launch
                }
            }

            if (progressInt >= 0) {
                updateReadingProgress(
                    progress = progressInt,
                    scrollPosition = progress,
                    index = index,
                    offset = offset
                )
            }
            lastRawScrollOffset = scrollOffset
        }
    }

    fun updateReadingProgress(
        progress: Int,
        scrollPosition: Float? = null,
        index: Int? = null,
        offset: Int? = null
    ): Unit {
        currentLibraryItemId?.let { itemId ->
            runCatching {
                val currentChapterUrl = _uiState.value.content?.url ?: ""
                val lastScroll = scrollPosition ?: _uiState.value.scrollPosition
                val lastIndex = index ?: _uiState.value.scrollIndex
                val lastOffset = offset ?: _uiState.value.scrollOffset

                libraryRepository.saveProgress(
                    itemId = itemId,
                    currentChapter = "",
                    progress = progress,
                    currentChapterUrl = currentChapterUrl,
                    lastScrollProgress = lastScroll,
                    lastReadIndex = lastIndex,
                    lastReadOffset = lastOffset
                )
            }
        }
    }

    fun clearError(): Unit {
        updateState { it.copy(error = null) }
    }

    fun retryLoad(): Unit {
        val url = _uiState.value.lastAttemptedUrl ?: _uiState.value.content?.url
        url?.let {
            loadContent(it, currentLibraryItemId)
        }
    }

    fun resetState(): Unit {
        _uiState.value = ReaderUiState()
        currentLibraryItemId = null
    }

    fun isContentCached(url: String): Boolean = contentRepository.isCached(url)

    fun clearCache(url: String): Unit {
        viewModelScope.launch { runCatching { contentRepository.clearCache(url) } }
    }

    fun clearAllCache(): Unit {
        viewModelScope.launch {
            runCatching { contentRepository.clearAllCache() }
                .onFailure { e -> updateState { it.copy(error = "Failed to clear cache: ${e.message}") } }
        }
    }

    suspend fun getCacheSize(): Long = contentRepository.getCacheSize()

    fun saveScrollPosition(position: Float): Unit {
        updateState { it.copy(scrollPosition = position) }
    }

    fun getScrollPosition(): Float = _uiState.value.scrollPosition

    fun seekToProgress(progress: Float): Unit {
        val targetPercent = progress.coerceIn(0f, 100f)
        val totalItems = _uiState.value.content?.paragraphs?.size ?: 0

        val preciseItemIndex = (targetPercent / 100f) * (totalItems - 1).coerceAtLeast(0)
        val roughIndex = preciseItemIndex.toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))

        updateState {
            it.copy(
                scrollPosition = targetPercent,
                scrollProgress = targetPercent.toInt(),
                scrollIndex = roughIndex,
                scrollOffset = 0,
                seekTrigger = System.currentTimeMillis()
            )
        }

        updateReadingProgress(
            progress = targetPercent.toInt(),
            scrollPosition = targetPercent,
            index = roughIndex,
            offset = 0
        )
    }

    override fun onCleared(): Unit {
        super.onCleared()
        val progress = _uiState.value.scrollProgress
        if (progress >= 0) updateReadingProgress(progress)
    }

    fun toggleControls(): Unit = updateState { it.copy(showControls = !it.showControls) }
    fun hideControls(): Unit = updateState { it.copy(showControls = false) }

    fun toggleReadingMode(): Unit {
        val newMode = !uiState.value.isPagedMode
        updateState { it.copy(isPagedMode = newMode) }
        currentLibraryItemId?.let { id ->
            viewModelScope.launch {
                libraryRepository.updateReadingMode(id, if (newMode) ReadingMode.PAGED else ReadingMode.VERTICAL)
            }
        }
    }

    fun toggleRtl(): Unit = updateState { it.copy(isRtl = !it.isRtl) }

    fun navigateToChapter(url: String, title: String): Unit {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = true
            libraryRepository.getItemByUrl(url)?.let { existingItem ->
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
                    } else loadContent(url, isSilent = false)
                }
            }
        }
    }

    fun loadFullChapterList(baseUrl: String, sourceName: String): Unit {
        viewModelScope.launch {
            runCatching {
                updateState { it.copy(isChaptersLoading = true) }
                val details = exploreRepository.getNovelDetails(baseUrl, sourceName)
                if (details != null && details.chapters.isNotEmpty()) {
                    updateState { it.copy(fullChapterList = details.chapters, isChaptersLoading = false) }
                    updateNavigationUrls()
                    currentLibraryItemId?.let { id ->
                        libraryRepository.getItemById(id)?.let { item ->
                            if (item.totalChapters != details.chapters.size) {
                                libraryRepository.updateItem(item.copy(totalChapters = details.chapters.size))
                            }
                        }
                    }
                } else updateState { it.copy(isChaptersLoading = false) }
            }.onFailure { updateState { it.copy(isChaptersLoading = false) } }
        }
    }

    private fun updateNavigationUrls(): Unit {
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
