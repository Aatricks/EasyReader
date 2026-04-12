package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.ui.theme.AccentTheme
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

    companion object {
        private val DOUBLE_NEWLINE_REGEX = Regex("""\n\s*\n""")
        private const val MIN_SCROLL_OFFSET_DELTA_PX = 8
        private const val MIN_SCROLL_PROGRESS_DELTA_PERCENT = 0.35f
    }

    // Current library item ID being read
    private var currentLibraryItemId: String? = null

    // Suppress auto navigation when restoring a saved position until user interacts
    private var suppressAutoNavUntilUserInteraction: Boolean = false
    private var restoredScrollPercent: Float = 0f

    // Track if we're explicitly navigating (not restoring from library)
    private var isExplicitNavigation: Boolean = false

    // Track last raw scroll offset (pixels) to detect actual user gesture direction
    private var lastRawScrollOffset: Float = -1f
    private var lastReportedIndex: Int = -1
    private var lastReportedOffsetPx: Int = -1
    private var lastReportedProgress: Float = -1f

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
                ),
                accentTheme = runCatching { AccentTheme.valueOf(preferencesManager.accentTheme) }.getOrDefault(
                    AccentTheme.MOSS
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
    data class ReaderSettingsState(
        val fontSize: Float,
        val lineHeight: Float,
        val fontFamily: String,
        val margins: Int,
        val paragraphSpacing: Float,
        val readerTheme: ReaderTheme,
        val accentTheme: AccentTheme,
        val isPagedMode: Boolean,
        val isRtl: Boolean
    )

    data class ReaderNavigationState(
        val canNavigateNext: Boolean,
        val canNavigatePrevious: Boolean,
        val isNavigating: Boolean,
        val fullChapterList: List<ChapterInfo>,
        val isChaptersLoading: Boolean,
        val isFullChapterListLoaded: Boolean,
        val baseNovelUrl: String,
        val sourceName: String
    )

    data class ReaderDialogState(
        val pendingExternalUrl: String?,
        val showExternalUrlConfirmation: Boolean,
        val pendingFileConfirmationUri: String?,
        val showFileConfirmationDialog: Boolean,
        val toastMessage: String?
    )

    data class ReaderProgressState(
        val scrollPosition: Float,
        val scrollProgress: Int,
        val scrollIndex: Int,
        val scrollOffset: Int,
        val seekTrigger: Long,
        val targetScrollPosition: Float?
    )

    data class ReaderUiState(
        val content: ChapterContent? = null,
        val isLoading: Boolean = true,
        val isNavigating: Boolean = false,
        val error: String? = null,
        val lastAttemptedUrl: String? = null,
        val lastFromBottom: Boolean = false,
        val lastIsExplicitNavigation: Boolean = false,
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
        val isFullChapterListLoaded: Boolean = false,
        val seekTrigger: Long = 0L,
        val targetScrollPosition: Float? = null,
        val fontSize: Float = 18f,
        val lineHeight: Float = 1.5f,
        val fontFamily: String = "Default",
        val margins: Int = 16,
        val paragraphSpacing: Float = 1.0f,
        val readerTheme: ReaderTheme = ReaderTheme.DARK,
        val accentTheme: AccentTheme = AccentTheme.MOSS,
        val pendingExternalUrl: String? = null,
        val showExternalUrlConfirmation: Boolean = false,
        val pendingFileConfirmationUri: String? = null,
        val showFileConfirmationDialog: Boolean = false
    ) {
        val settings: ReaderSettingsState
            get() = ReaderSettingsState(
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                margins = margins,
                paragraphSpacing = paragraphSpacing,
                readerTheme = readerTheme,
                accentTheme = accentTheme,
                isPagedMode = isPagedMode,
                isRtl = isRtl
            )

        val navigation: ReaderNavigationState
            get() = ReaderNavigationState(
                canNavigateNext = canNavigateNext,
                canNavigatePrevious = canNavigatePrevious,
                isNavigating = isNavigating,
                fullChapterList = fullChapterList,
                isChaptersLoading = isChaptersLoading,
                isFullChapterListLoaded = isFullChapterListLoaded,
                baseNovelUrl = baseNovelUrl,
                sourceName = sourceName
            )

        val dialogs: ReaderDialogState
            get() = ReaderDialogState(
                pendingExternalUrl = pendingExternalUrl,
                showExternalUrlConfirmation = showExternalUrlConfirmation,
                pendingFileConfirmationUri = pendingFileConfirmationUri,
                showFileConfirmationDialog = showFileConfirmationDialog,
                toastMessage = toastMessage
            )

        val progressState: ReaderProgressState
            get() = ReaderProgressState(
                scrollPosition = scrollPosition,
                scrollProgress = scrollProgress,
                scrollIndex = scrollIndex,
                scrollOffset = scrollOffset,
                seekTrigger = seekTrigger,
                targetScrollPosition = targetScrollPosition
            )
    }

    fun requestOpenFile(uri: String): Unit {
        updateState { it.copy(pendingFileConfirmationUri = uri, showFileConfirmationDialog = true) }
    }

    fun dismissFileConfirmation(): Unit {
        updateState { it.copy(pendingFileConfirmationUri = null, showFileConfirmationDialog = false) }
    }

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

    fun updateAccentTheme(newAccentTheme: AccentTheme): Unit {
        preferencesManager.accentTheme = newAccentTheme.name
        updateState { it.copy(accentTheme = newAccentTheme) }
    }

    fun clearToast(): Unit {
        updateState { it.copy(toastMessage = null) }
    }

    fun openChapterFromStart(
        url: String,
        libraryItemId: String? = null,
        fromBottom: Boolean = false,
        isSilent: Boolean = false
    ): Unit = loadContent(
        url = url,
        libraryItemId = libraryItemId,
        fromBottom = fromBottom,
        isSilent = isSilent,
        isExplicitNavigation = true
    )

    fun loadContent(
        url: String,
        libraryItemId: String? = null,
        fromBottom: Boolean = false,
        isSilent: Boolean = false,
        isExplicitNavigation: Boolean = this.isExplicitNavigation
    ): Unit {
        loadJob?.cancel()
        progressUpdateJob?.cancel()
        loadJob = viewModelScope.launch {
            performLoad(
                url = url,
                libraryItemId = libraryItemId,
                fromBottom = fromBottom,
                isSilent = isSilent,
                isExplicitNavigation = isExplicitNavigation
            )
        }
    }

    private suspend fun performLoad(
        url: String,
        libraryItemId: String?,
        fromBottom: Boolean,
        isSilent: Boolean,
        isExplicitNavigation: Boolean,
        preloadedResult: ContentResult.Success? = null
    ): Unit {
        this@ReaderViewModel.isExplicitNavigation = isExplicitNavigation
        if (handleEpubUrl(url, libraryItemId, fromBottom, isSilent)) return

        saveCurrentProgress()

        if (!isSilent) {
            closeContent(_uiState.value.content)
        }

        updateState {
            it.copy(
                isLoading = !isSilent,
                error = null,
                lastAttemptedUrl = url,
                lastFromBottom = fromBottom,
                lastIsExplicitNavigation = isExplicitNavigation,
                content = if (isSilent) it.content else null
            )
        }

        val result = preloadedResult ?: run {
            val pdfResumeIndex = resolvePdfResumeIndex(url, libraryItemId, isExplicitNavigation)
            if (pdfResumeIndex != null) {
                contentRepository.loadContent(url, pdfResumeIndex)
            } else {
                contentRepository.loadContent(url)
            }
        }

        when (result) {
            is ContentResult.Success -> {
                updateState { it.copy(lastAttemptedUrl = null) }
                handleLoadSuccess(result, libraryItemId, fromBottom)
            }

            is ContentResult.Error -> handleLoadError(result)
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

    private suspend fun resolvePdfResumeIndex(
        url: String,
        libraryItemId: String?,
        isExplicitNavigation: Boolean
    ): Int? {
        if (isExplicitNavigation) return null

        val libraryItem = libraryItemId?.let { libraryRepository.getItemById(it) }
            ?: libraryRepository.getItemByUrl(url)
            ?: return null

        return libraryItem.takeIf { it.contentType == ContentType.PDF }?.lastReadIndex
    }

    private fun isPlaceholderAtCurrentPosition(index: Int? = null): Boolean {
        val lastIndex = index ?: _uiState.value.scrollIndex
        val paragraphs = _uiState.value.content?.paragraphs ?: return false
        val currentItem = paragraphs.getOrNull(lastIndex)
        return currentItem is ContentElement.Placeholder || 
               (currentItem is ContentElement.Text && currentItem.content.startsWith("Loading page"))
    }

    private suspend fun saveCurrentProgress(): Unit {
        val prevItemId = currentLibraryItemId ?: return
        val prevContent = _uiState.value.content ?: return

        if (isPlaceholderAtCurrentPosition()) return

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
        result: ContentResult.Success,
        libraryItemId: String?,
        fromBottom: Boolean
    ): Unit {
        closeContent(_uiState.value.content)
        var effectiveId = libraryItemId ?: libraryRepository.getItemByUrl(result.url)?.id

        if (isExplicitNavigation && currentLibraryItemId != null) {
            val currentItem = libraryRepository.getItemById(currentLibraryItemId!!)
            if (currentItem != null && currentItem.url != result.url && currentItem.contentType == ContentType.WEB) {
                // We are navigating to a new chapter. Ensure it's in the library.
                val existing = libraryRepository.getItemByUrl(result.url)
                effectiveId = existing?.id ?: addChapterToLibrary(result.url, result.title, isNext = !fromBottom) ?: effectiveId
            }
        }

        currentLibraryItemId = effectiveId

        val content = ChapterContent(
            paragraphs = result.elements,
            title = result.title,
            url = result.url,
            nextChapterUrl = contentRepository.incrementChapterUrl(result.url),
            previousChapterUrl = contentRepository.decrementChapterUrl(result.url),
            preCalculatedTextCount = result.textCount,
            preCalculatedImageCount = result.imageCount
        )

        val libraryItem = effectiveId?.let { libraryRepository.getItemById(it) }
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

        var currentFullList = _uiState.value.fullChapterList
        // If we switched novels, discard the old list
        if (_uiState.value.baseTitle != baseTitle) {
            currentFullList = emptyList()
            updateState { it.copy(isFullChapterListLoaded = false) }
        }

        if (currentFullList.isEmpty() && baseTitle.isNotBlank()) {
            val libChapters = libraryRepository.getChaptersByBaseTitle(baseTitle)
            if (libChapters.isNotEmpty()) {
                currentFullList = libChapters.map {
                    ChapterInfo(
                        title = it.title,
                        url = it.url,
                        number = TextUtils.extractChapterNumber(it.currentChapter.ifBlank { it.title })
                    )
                }
            }
        }

        updateState {
            it.copy(
                content = content,
                isLoading = false,
                isNavigating = false,
                error = null,
                lastIsExplicitNavigation = false,
                canNavigateNext = content.hasNextChapter(),
                canNavigatePrevious = content.hasPreviousChapter(),
                scrollPosition = initialScroll.position,
                scrollProgress = initialScroll.progress,
                scrollIndex = initialScroll.index,
                scrollOffset = initialScroll.offset,
                targetScrollPosition = initialScroll.targetPosition,
                hasReachedQuarterScreen = fromBottom || initialScroll.progress >= 25,
                novelName = novelName,
                chapterTitle = chapterTitle,
                baseTitle = baseTitle,
                baseNovelUrl = libraryItem?.baseNovelUrl ?: "",
                sourceName = libraryItem?.sourceName ?: "",
                isPagedMode = isPaged,
                fullChapterList = currentFullList
            )
        }

        updateNavigationUrls()
        maybeWarmNextChapter(_uiState.value.content?.nextChapterUrl)

        libraryItem?.let { item ->
            if (item.baseNovelUrl.isNotBlank() && item.sourceName.isNotBlank()) {
                loadFullChapterList(item.baseNovelUrl, item.sourceName)
            }
            libraryRepository.markAsCurrentlyReading(item.id)
            performAutoDeletion(content.url, novelName, chapterTitle)
        }

        isExplicitNavigation = false
    }

    private fun maybeWarmNextChapter(nextChapterUrl: String?) {
        if (nextChapterUrl.isNullOrBlank() || !nextChapterUrl.startsWith("http")) return

        viewModelScope.launch {
            val cacheState = contentRepository.inspectCache(nextChapterUrl)
            if (!cacheState.isComplete) {
                contentRepository.prefetch(nextChapterUrl, PrefetchMode.SPECULATIVE)
            }
        }
    }

    private fun performAutoDeletion(currentUrl: String, novelName: String, chapterTitle: String) {
        val baseTitle = _uiState.value.baseTitle.ifBlank { novelName }
        if (baseTitle.isBlank()) return

        viewModelScope.launch {
            delay(1000) // Ensure progress is saved if navigating from a finished chapter

            val currentChapterNumber = TextUtils.extractChapterNumber(chapterTitle)
                ?: TextUtils.extractChapterNumber(currentUrl)
                ?: return@launch

            val toDelete = computeAutoDeleteCandidates(
                allItems = libraryRepository.libraryItems.value,
                baseTitle = baseTitle,
                currentUrl = currentUrl,
                currentChapterNumber = currentChapterNumber
            )

            if (toDelete.isNotEmpty()) {
                contentRepository.clearCachesForUrls(toDelete.map { it.url })
                val ids = toDelete.map { it.id }.toSet()
                libraryRepository.removeItems(ids)
            }
        }
    }

    private fun handleLoadError(result: ContentResult.Error): Unit {
        updateState { it.copy(isLoading = false, isNavigating = false, error = result.message) }
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
        val offset: Int,
        val targetPosition: Float? = null
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
                offset = libraryItem.lastReadOffset,
                targetPosition = libraryItem.lastScrollPosition
            )
        } else {
            restoredScrollPercent = if (fromBottom) 100f else 0f
            suppressAutoNavUntilUserInteraction = true
            ScrollState(
                index = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0,
                position = if (fromBottom) 100f else 0f,
                progress = if (fromBottom) 100 else 0,
                offset = if (fromBottom) 10000000 else 0,
                targetPosition = if (fromBottom) 100f else 0f
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
                loadContent(url, existingItem.id, fromBottom = fromBottom, isSilent = true, isExplicitNavigation = true)
                return@launch
            }

            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(url)

            when (result) {
                is ContentResult.Success -> {
                    val itemId = addChapterToLibrary(url, result.title, isNext = isNext)
                    performLoad(
                        url = url,
                        libraryItemId = itemId,
                        fromBottom = fromBottom,
                        isSilent = true,
                        isExplicitNavigation = true,
                        preloadedResult = result
                    )
                }

                is ContentResult.Error -> {
                    updateState {
                        it.copy(
                            isNavigating = false,
                            lastAttemptedUrl = url,
                            lastFromBottom = fromBottom,
                            lastIsExplicitNavigation = true
                        )
                    }
                    if (result.message.contains("404")) {
                        val msg = if (isNext) "Next chapter not found (404)" else "Previous chapter not found (404)"
                        updateState { it.copy(toastMessage = msg) }
                    } else {
                        handleLoadError(result)
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
                handleLoadError(ContentResult.Error("Failed to load EPUB structure"))
                return@launch
            }

            val chapter = contentRepository.loadEpubChapterFull(epubPath, href)
            if (chapter == null) {
                handleLoadError(ContentResult.Error("Failed to load chapter content"))
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

            closeContent(_uiState.value.content)
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
                    targetScrollPosition = initialScroll.targetPosition,
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
            val parts = formatted.split(DOUBLE_NEWLINE_REGEX).map { it.trim() }.filter { it.isNotBlank() }
            parts.forEach { p -> formattedElements.add(ContentElement.Text(p)) }
            textBuffer.clear()
        }

        for (el in rawElements) {
            when (el) {
                is ContentElement.Text -> textBuffer.add(el.content)
                is ContentElement.Placeholder, is ContentElement.PageContent -> {
                    flushTextBuffer()
                    formattedElements.add(el)
                }
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
        updateState { it.copy(targetScrollPosition = null) }
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int,
        canScrollForward: Boolean = true
    ): Unit {
        val deltaRaw = if (lastRawScrollOffset < 0f) 0f else scrollOffset - lastRawScrollOffset
        val isScrollingDown = deltaRaw > 0f

        val progress = when {
            !canScrollForward -> 100f
            maxScrollOffset > viewportHeight -> ((scrollOffset / (maxScrollOffset - viewportHeight)) * 100f).coerceIn(
                0f,
                100f
            )

            maxScrollOffset > 0 -> 100f
            else -> 0f
        }

        val hasReached = progress >= 25f
        val progressInt = progress.toInt()
        val isMicroDelta = index == lastReportedIndex &&
            kotlin.math.abs(offset - lastReportedOffsetPx) < MIN_SCROLL_OFFSET_DELTA_PX &&
            kotlin.math.abs(progress - lastReportedProgress) < MIN_SCROLL_PROGRESS_DELTA_PERCENT

        if (isMicroDelta) {
            lastRawScrollOffset = scrollOffset
            return
        }

        lastReportedIndex = index
        lastReportedOffsetPx = offset
        lastReportedProgress = progress

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
                lastRawScrollOffset = scrollOffset
                return@launch
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

                if (isPlaceholderAtCurrentPosition(lastIndex)) return@runCatching

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
        val fromBottom = _uiState.value.lastFromBottom
        val isExplicit = _uiState.value.lastIsExplicitNavigation
        url?.let {
            loadContent(it, currentLibraryItemId, fromBottom = fromBottom, isExplicitNavigation = isExplicit)
        }
    }

    fun resetState(): Unit {
        closeContent(_uiState.value.content)
        _uiState.value = ReaderUiState()
        currentLibraryItemId = null
        lastRawScrollOffset = -1f
        lastReportedIndex = -1
        lastReportedOffsetPx = -1
        lastReportedProgress = -1f
    }

    private fun closeContent(content: ChapterContent?) {
        (content?.paragraphs as? java.io.Closeable)?.close()
    }

    fun isContentCached(url: String): Boolean = contentRepository.isCached(url)

    fun prefetchVisibleImage(imageUrl: String, pageUrl: String): Unit {
        if (!imageUrl.startsWith("http")) return

        viewModelScope.launch {
            runCatching { contentRepository.warmImage(imageUrl, pageUrl) }
        }
    }

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
        val offset = if (targetPercent == 100f) 10000000 else 0

        updateState {
            it.copy(
                scrollPosition = targetPercent,
                scrollProgress = targetPercent.toInt(),
                scrollIndex = roughIndex,
                scrollOffset = offset,
                seekTrigger = System.currentTimeMillis(),
                targetScrollPosition = if (targetPercent == 100f) 100f else null
            )
        }

        updateReadingProgress(
            progress = targetPercent.toInt(),
            scrollPosition = targetPercent,
            index = roughIndex,
            offset = offset
        )
    }

    override fun onCleared(): Unit {
        super.onCleared()
        closeContent(_uiState.value.content)
        val progress = _uiState.value.scrollProgress
        if (progress >= 0) updateReadingProgress(progress)
    }

    fun toggleControls(): Unit = updateState { it.copy(showControls = !it.showControls) }
    fun hideControls(): Unit = updateState { it.copy(showControls = false) }

    fun toggleReadingMode(): Unit {
        val newMode = !uiState.value.isPagedMode
        setPagedMode(newMode)
    }

    fun setPagedMode(isPagedMode: Boolean): Unit {
        val newMode = isPagedMode
        updateState { it.copy(isPagedMode = newMode) }
        currentLibraryItemId?.let { id ->
            viewModelScope.launch {
                libraryRepository.updateReadingMode(id, if (newMode) ReadingMode.PAGED else ReadingMode.VERTICAL)
            }
        }
    }

    fun toggleRtl(): Unit = setRtl(!uiState.value.isRtl)

    fun setRtl(isRtl: Boolean): Unit = updateState { it.copy(isRtl = isRtl) }

    fun navigateToChapter(url: String, title: String): Unit {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isExplicitNavigation = false
            libraryRepository.getItemByUrl(url)?.let { existingItem ->
                loadContent(url, existingItem.id, isExplicitNavigation = false)
                return@launch
            }
            updateState { it.copy(isNavigating = true) }
            val result = contentRepository.loadContent(url)
            when (result) {
                is ContentResult.Success -> {
                    val itemId = addChapterToLibrary(url, result.title, isNext = true)
                    performLoad(
                        url = url,
                        libraryItemId = itemId,
                        fromBottom = false,
                        isSilent = true,
                        isExplicitNavigation = false,
                        preloadedResult = result
                    )
                }

                is ContentResult.Error -> {
                    updateState {
                        it.copy(
                            isNavigating = false,
                            lastAttemptedUrl = url,
                            lastFromBottom = false,
                            lastIsExplicitNavigation = false
                        )
                    }
                    if (result.message.contains("404")) {
                        updateState { it.copy(toastMessage = "Chapter not found (404)") }
                    } else handleLoadError(result)
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
                    updateState { it.copy(fullChapterList = details.chapters, isChaptersLoading = false, isFullChapterListLoaded = true) }
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
        if (!state.isFullChapterListLoaded) return
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

internal fun computeAutoDeleteCandidates(
    allItems: List<LibraryItem>,
    baseTitle: String,
    currentUrl: String,
    currentChapterNumber: Double
): List<LibraryItem> {
    return allItems
        .asSequence()
        .filter { item ->
            item.baseTitle == baseTitle &&
                item.contentType == ContentType.WEB &&
                item.url != currentUrl &&
                item.progress == 100
        }
        .filter { item ->
            val otherNumber = TextUtils.extractChapterNumber(item.currentChapter)
                ?: TextUtils.extractChapterNumber(item.url)
                ?: return@filter false

            // Keep the immediately previous chapter; only prune chapters 2+ behind the current one.
            (currentChapterNumber - otherNumber) > 1
        }
        .toList()
}
