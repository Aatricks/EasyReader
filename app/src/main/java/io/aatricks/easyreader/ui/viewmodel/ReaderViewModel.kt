package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.ChapterListCache
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.ui.theme.AccentTheme
import io.aatricks.easyreader.util.healCurrentChapterLabel
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.util.resolveChapterLabelFromList
import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.UrlSecurity
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.PAGED_POSITION_ITEM_SIZE_PX
import io.aatricks.easyreader.util.FieldUpdate
import io.aatricks.easyreader.util.computeDownloadCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val preferencesManager: PreferencesManager,
    private val chapterListCache: ChapterListCache,
    private val imageDimensionCache: ImageDimensionCacheRepository
) : BaseViewModel<ReaderViewModel.ReaderUiState>(ReaderUiState()) {
    private val progressController = ReaderProgressController(libraryRepository, viewModelScope)
    val progressState: StateFlow<ReaderProgressState> = progressController.progressState

    companion object {
        private const val TAG = "ReaderViewModel"
        private val DOUBLE_NEWLINE_REGEX = Regex("""\n\s*\n""")
    }

    // Current library item ID being read
    private var currentLibraryItemId: String?
        get() = progressController.currentLibraryItemId
        set(value) { progressController.currentLibraryItemId = value }

    val userHasDragged: Boolean
        get() = progressController.userHasDragged

    val restoreInProgress: Boolean
        get() = progressController.restoreInProgress

    fun markUserDragged() {
        progressController.markUserDragged()
    }

    fun markRestoreDone() {
        progressController.markRestoreDone()
    }

    fun beginRestore() {
        progressController.beginRestore()
    }

    // Resolved intrinsic dimensions keyed by image URL, one Compose State per URL. A
    // ReaderImageView subscribes to its own url's State, so a write only recomposes that one
    // image — and an item scrolled away and back is sized correctly on its FIRST composition
    // (no collapse to the loading placeholder + relayout). This is what keeps fast up/down
    // dragging smooth; the debounced `content` rebuild below stays only for persistence /
    // restore math.
    private val imageDimensionManager = ImageDimensionManager(
        scope = viewModelScope,
        imageDimensionCache = imageDimensionCache,
        applyContentDimensions = ::updateCurrentContentImageDimensions,
    )

    fun imageDimensionState(imageUrl: String): androidx.compose.runtime.State<Pair<Int, Int>?> =
        imageDimensionManager.dimensionState(imageUrl)

    fun persistImageDimensions(imageUrl: String, width: Int, height: Int) =
        imageDimensionManager.persistImageDimensions(imageUrl, width, height)

    private fun updateCurrentContentImageDimensions(updates: Map<String, Pair<Int, Int>>) {
        updateState { state ->
            val content = state.content ?: return@updateState state
            var changed = false
            val updatedParagraphs = content.paragraphs.map { element ->
                val resolved = element.withResolvedImageDimensions(updates)
                if (resolved !== element) changed = true
                resolved
            }
            if (!changed) {
                state
            } else {
                state.copy(content = content.copy(paragraphs = updatedParagraphs))
            }
        }
    }

    private fun ContentElement.withResolvedImageDimensions(updates: Map<String, Pair<Int, Int>>): ContentElement {
        return when (this) {
            is ContentElement.Image -> {
                val (width, height) = updates[url] ?: return this
                if (this.width <= 0 || this.height <= 0) {
                    copy(width = width, height = height)
                } else {
                    this
                }
            }

            is ContentElement.ImageGroup -> {
                val updatedImages = images.map { img ->
                    val (width, height) = updates[img.url] ?: return@map img
                    if (img.width <= 0 || img.height <= 0) {
                        img.copy(width = width, height = height)
                    } else {
                        img
                    }
                }
                if (updatedImages == images) this else copy(images = updatedImages)
            }

            is ContentElement.PageContent -> {
                val updatedElements = elements.map { it.withResolvedImageDimensions(updates) }
                if (updatedElements == elements) this else copy(elements = updatedElements)
            }

            else -> this
        }
    }

    // Track if we're explicitly navigating (not restoring from library)
    private var isExplicitNavigation: Boolean = false

    // Track last raw scroll offset (pixels) to detect actual user gesture direction
    private var lastRawScrollOffset: Float = -1f

    // Job for tracking content loading
    private var loadJob: Job? = null

    private fun applyReaderSettings(snapshot: io.aatricks.easyreader.data.local.ReaderSettingsSnapshot) {
        updateState {
            it.copy(
                fontSize = snapshot.fontSize,
                lineHeight = snapshot.lineHeight,
                fontFamily = snapshot.fontFamily,
                margins = snapshot.margins,
                paragraphSpacing = snapshot.paragraphSpacing,
                readerTheme = runCatching { ReaderTheme.valueOf(snapshot.readerTheme) }
                    .getOrDefault(ReaderTheme.DARK),
                accentTheme = runCatching { AccentTheme.valueOf(snapshot.accentTheme) }
                    .getOrDefault(AccentTheme.MOSS)
            )
        }
    }

    init {
        // Seed synchronously so the first frame of the reader renders with the
        // correct font/theme rather than the data class defaults.
        applyReaderSettings(preferencesManager.readerSettings.value)
        // Reactive: any SharedPreferences mutation (including bulk restore via
        // batchUpdateReaderSettings) re-emits a snapshot and the uiState follows.
        viewModelScope.launch {
            preferencesManager.readerSettings
                .collect { snapshot -> applyReaderSettings(snapshot) }
        }

        // Load last read item. Fast path: SharedPreferences mirrors the last-read URL on every
        // successful chapter load, so cold launch can fire loadContent without waiting for
        // Room's getCurrentlyReading query. Falls back to Room when prefs are empty (fresh
        // install, post-clear) and reconciles async so a stale prefs entry self-corrects.
        val cachedLastUrl = preferencesManager.lastReadUrl
        val cachedLastItemId = preferencesManager.lastReadLibraryItemId
        if (!cachedLastUrl.isNullOrBlank()) {
            loadContent(cachedLastUrl, cachedLastItemId)
            viewModelScope.launch {
                val canonical = libraryRepository.getCurrentlyReading() ?: return@launch
                val canonicalUrl = canonical.currentChapterUrl.ifBlank { canonical.url }
                if (canonicalUrl.isNotBlank() && canonicalUrl != cachedLastUrl) {
                    loadContent(canonicalUrl, canonical.id)
                }
            }
        } else {
            viewModelScope.launch {
                libraryRepository.getCurrentlyReading()?.let { last ->
                    val loadUrl = last.currentChapterUrl.ifBlank { last.url }
                    loadContent(loadUrl, last.id)
                } ?: updateState { it.copy(isLoading = false) }
            }
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
        val restoreElementKey: String = "",
        // Sentinel FRACTION_UNKNOWN (-1f) = no restore pending; 0..1 = pending intra-item fraction.
        val restoreOffsetFraction: Float = io.aatricks.easyreader.data.model.FRACTION_UNKNOWN,
        val isPreciseRestore: Boolean = false,
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
                scrollElementKey = restoreElementKey,
                scrollOffsetFraction = restoreOffsetFraction,
                isPreciseRestore = isPreciseRestore,
                firstVisibleItemSize = 0,
                seekTrigger = seekTrigger,
                targetScrollPosition = targetScrollPosition
            )
    }

    private fun syncProgressState(state: ReaderProgressState) {
        progressController.syncProgressState(state)
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
        if (preferencesManager.fontFamily == newFamily) return
        preferencesManager.fontFamily = newFamily
        updateState { it.copy(fontFamily = newFamily, toastMessage = "Font: $newFamily") }
    }

    fun updateMargins(newMargins: Int): Unit {
        val margins = newMargins.coerceIn(4, 64)
        preferencesManager.margins = margins
        updateState { it.copy(margins = margins) }
    }

    fun updateParagraphSpacing(newSpacing: Float): Unit {
        val spacing = newSpacing.coerceIn(0.0f, 3.0f)
        preferencesManager.paragraphSpacing = spacing
        updateState { it.copy(paragraphSpacing = spacing) }
    }

    fun updateReaderTheme(newTheme: ReaderTheme): Unit {
        if (preferencesManager.readerTheme == newTheme.name) return
        preferencesManager.readerTheme = newTheme.name
        val label = newTheme.name.lowercase().replaceFirstChar { it.uppercase() }
        updateState { it.copy(readerTheme = newTheme, toastMessage = "Theme: $label") }
    }

    fun updateAccentTheme(newAccentTheme: AccentTheme): Unit {
        if (preferencesManager.accentTheme == newAccentTheme.name) return
        preferencesManager.accentTheme = newAccentTheme.name
        updateState { it.copy(accentTheme = newAccentTheme, toastMessage = "Accent: ${newAccentTheme.displayName}") }
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
        isExplicitNavigation: Boolean = false,
        resetWebStateBeforeLoad: Boolean = false
    ): Unit {
        loadJob?.cancel()
        progressController.cancelProgressUpdate()
        loadJob = viewModelScope.launch {
            performLoad(
                url = url,
                libraryItemId = libraryItemId,
                fromBottom = fromBottom,
                isSilent = isSilent,
                isExplicitNavigation = isExplicitNavigation,
                resetWebStateBeforeLoad = resetWebStateBeforeLoad
            )
        }
    }

    private suspend fun performLoad(
        url: String,
        libraryItemId: String?,
        fromBottom: Boolean,
        isSilent: Boolean,
        isExplicitNavigation: Boolean,
        preloadedResult: ContentResult.Success? = null,
        resetWebStateBeforeLoad: Boolean = false
    ): Unit {
        try {
            this@ReaderViewModel.isExplicitNavigation = isExplicitNavigation
            if (handleEpubUrl(url, libraryItemId, fromBottom, isSilent)) return

            progressController.saveCurrentProgress(_uiState.value.content)

            if (!isSilent) {
                closeContent(_uiState.value.content)
            }

            if (resetWebStateBeforeLoad) {
                contentRepository.resetWebLoadState(url, clearCachedHtml = true)
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
            currentCoroutineContext().ensureActive()

            when (result) {
                is ContentResult.Success -> {
                    updateState { it.copy(lastAttemptedUrl = null) }
                    handleLoadSuccess(result, libraryItemId, fromBottom)
                }

                is ContentResult.Error -> handleLoadError(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleLoadError(ContentResult.Error("Failed to load chapter content", e))
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

    private suspend fun saveCurrentProgress(): Unit {
        progressController.saveCurrentProgress(_uiState.value.content)
    }

    private suspend fun handleLoadSuccess(
        result: ContentResult.Success,
        libraryItemId: String?,
        fromBottom: Boolean
    ): Unit {
        closeContent(_uiState.value.content)
        var effectiveId = libraryItemId ?: libraryRepository.getItemByUrl(result.url)?.id

        val pinnedLibraryItemId = currentLibraryItemId
        if (isExplicitNavigation && pinnedLibraryItemId != null) {
            val currentItem = libraryRepository.getItemById(pinnedLibraryItemId)
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

        val initialPosition = progressController.calculateInitialPosition(content, libraryItem, fromBottom, isExplicitNavigation)

        var currentFullList = _uiState.value.fullChapterList
        // If we switched novels, discard the old list
        if (_uiState.value.baseTitle != baseTitle) {
            currentFullList = emptyList()
            updateState { it.copy(isFullChapterListLoaded = false) }
        }

        if (currentFullList.isEmpty() && baseTitle.isNotBlank()) {
            val libChapters = libraryRepository.getChaptersByBaseTitle(baseTitle)
            if (libChapters.isNotEmpty()) {
                currentFullList = normalizeChapterList(
                    libChapters.map {
                        ChapterInfo(
                            title = it.title,
                            url = it.url,
                            number = TextUtils.extractChapterNumber(it.currentChapter.ifBlank { it.title })
                        )
                    }
                )
            }
        }

        // Prefer the chapter list's label when the content/URL-derived one carries the wrong number
        // (Novelight reading URLs are /book/chapter/{id}, so the id leaks in as the chapter number).
        val resolvedChapterTitle =
            resolveChapterLabelFromList(content.url, chapterTitle, currentFullList) ?: chapterTitle

        updateState {
            it.copy(
                content = content,
                isLoading = false,
                isNavigating = false,
                error = null,
                lastIsExplicitNavigation = false,
                canNavigateNext = content.hasNextChapter(),
                canNavigatePrevious = content.hasPreviousChapter(),
                scrollPosition = initialPosition.scrollPosition,
                scrollProgress = initialPosition.scrollProgress,
                scrollIndex = initialPosition.scrollIndex,
                restoreElementKey = initialPosition.scrollElementKey,
                restoreOffsetFraction = initialPosition.scrollOffsetFraction,
                isPreciseRestore = initialPosition.isPreciseRestore,
                targetScrollPosition = initialPosition.targetScrollPosition,
                hasReachedQuarterScreen = fromBottom || initialPosition.scrollProgress >= 25,
                novelName = novelName,
                chapterTitle = resolvedChapterTitle,
                baseTitle = baseTitle,
                baseNovelUrl = libraryItem?.baseNovelUrl ?: "",
                sourceName = libraryItem?.sourceName ?: "",
                isPagedMode = isPaged,
                fullChapterList = currentFullList
            )
        }
        syncProgressState(initialPosition)

        // Prune only AFTER the new content is committed to uiState: during the (suspending)
        // load above the old chapter is still composed, and pruning it early would strip its
        // shared dimensions mid-display while late decodes re-inserted just-pruned entries.
        // A failed load never reaches this line, so an on-screen chapter is never pruned.
        imageDimensionManager.pruneForChapter(content.getAllImageUrls().toSet())

        updateNavigationUrls()
        maybeWarmNextChapter(_uiState.value.content?.nextChapterUrl)

        // Mirror the just-loaded chapter to SharedPreferences so the next cold launch can
        // restore without waiting for Room. Written unconditionally (incl. non-library
        // chapters) — relaunching the same external URL is the most common case to optimise.
        preferencesManager.batchUpdateLastRead(content.url, effectiveId)

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
            // Skip speculative prefetch for items the user explicitly downloaded — the persistent
            // copy is the source of truth and shouldn't trigger network calls on every open.
            if (libraryRepository.getItemByUrl(nextChapterUrl)?.isDownloaded == true) return@launch
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
            delay(1000) // Let a just-left chapter's progress write settle before reading the library.

            val currentChapterNumber = resolveCurrentChapterNumber(chapterTitle, currentUrl)
                ?: return@launch

            val plan = computeDownloadCleanup(
                allItems = libraryRepository.libraryItems.value,
                fullChapterList = _uiState.value.fullChapterList,
                baseTitle = baseTitle,
                currentUrl = currentUrl,
                currentChapterNumber = currentChapterNumber
            )

            // Free downloaded files for old, read chapters but keep the library row + its
            // progress. Downloads still in flight are left alone.
            val toFree = plan.downloadsToFree.filterNot { contentRepository.isUserDownloadInFlight(it.url) }
            if (toFree.isNotEmpty()) {
                contentRepository.clearCachesAndDownloadsForUrls(toFree.map { it.url })
                toFree.forEach { libraryRepository.markDownloaded(it.id, false) }
            }

            // Evict speculative/partial caches for chapters that are NOT in the library but live in
            // the current novel's chapter list; otherwise their cache files accumulate forever.
            if (plan.speculativeCacheUrls.isNotEmpty()) {
                contentRepository.clearCachesForUrls(plan.speculativeCacheUrls)
            }
        }
    }

    /**
     * Chapter number of the chapter being read. Prefers the current library row's
     * [resolvedChapterNumber] so the comparison shares the app-wide numbering scheme, falling back
     * to parsing the loaded title/URL only when no row is available.
     */
    private suspend fun resolveCurrentChapterNumber(chapterTitle: String, currentUrl: String): Double? {
        val item = currentLibraryItemId?.let { libraryRepository.getItemById(it) }
        return item?.resolvedChapterNumber()
            ?: TextUtils.extractChapterNumber(chapterTitle)
            ?: TextUtils.extractChapterNumber(currentUrl)
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

    fun navigateToNextChapter(): Unit = navigateToAdjacentChapter(isNext = true)
    fun navigateToPreviousChapter(fromBottom: Boolean = false): Unit =
        navigateToAdjacentChapter(isNext = false, fromBottom = fromBottom)

    private fun navigateToAdjacentChapter(isNext: Boolean, fromBottom: Boolean = false): Unit {
        updateNavigationUrls()
        val url = if (isNext) _uiState.value.content?.nextChapterUrl else _uiState.value.content?.previousChapterUrl
        if (url == null) return

        loadJob?.cancel()
        progressController.cancelProgressUpdate()
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
                    isExplicitNavigation = false
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
        progressController.cancelProgressUpdate()
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

            // Canonicalize the loaded href to the owning TOC entry so the chapter list
            // can highlight it by exact URL match — sub-anchors and split-chapter
            // spine segments otherwise produce URLs that never appear in the TOC.
            val canonicalHref = epubBook.findContainingTocHref(chapter.href)
                ?: epubBook.findTocItemByHref(chapter.href)?.href
                ?: chapter.href

            val content = ChapterContent(
                paragraphs = formatEpubElements(chapter.content),
                title = chapter.title,
                url = "$epubPath#$canonicalHref",
                nextChapterUrl = chapter.nextHref?.let { "$epubPath#${it}" }
                    ?: epubBook.getNextHref(href)?.let { "$epubPath#${it}" },
                previousChapterUrl = chapter.previousHref?.let { "$epubPath#${it}" }
                    ?: epubBook.getPreviousHref(href)?.let { "$epubPath#${it}" }
            )

            val tocChapterList = epubBook.getFlatToc()
                .map { ChapterInfo(title = it.title, url = "$epubPath#${it.href}") }

            val libraryItem = effectiveLibraryItemId?.let { libraryRepository.getItemById(it) }
            val baseTitle = libraryItem?.baseTitle?.ifBlank { null }
                ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.EPUB) }
                ?: ""

            val novelName = baseTitle.ifBlank { content.title ?: libraryItem?.title ?: "" }
            val chapterTitle = TextUtils.cleanChapterTitle(content.title, novelName).ifBlank {
                libraryItem?.currentChapter ?: ""
            }

            val initialPosition = progressController.calculateInitialPosition(content, libraryItem, fromBottom, isExplicitNavigation)

            closeContent(_uiState.value.content)
            updateState {
                it.copy(
                    content = content,
                    isLoading = false,
                    isNavigating = false,
                    error = null,
                    canNavigateNext = content.hasNextChapter(),
                    canNavigatePrevious = content.hasPreviousChapter(),
                    scrollPosition = initialPosition.scrollPosition,
                    scrollProgress = initialPosition.scrollProgress,
                    scrollIndex = initialPosition.scrollIndex,
                    restoreElementKey = initialPosition.scrollElementKey,
                    restoreOffsetFraction = initialPosition.scrollOffsetFraction,
                    targetScrollPosition = initialPosition.targetScrollPosition,
                    hasReachedQuarterScreen = fromBottom || initialPosition.scrollProgress >= 25,
                    novelName = novelName,
                    chapterTitle = chapterTitle,
                    baseTitle = baseTitle,
                    // Keep isFullChapterListLoaded false so updateNavigationUrls — which
                    // assumes spine-ordered web chapter lists — does not overwrite the
                    // next/previous URLs we just computed from the EPUB spine.
                    fullChapterList = tocChapterList
                )
            }
            syncProgressState(initialPosition)

            // After the content swap, for the same reasons as in handleLoadSuccess.
            imageDimensionManager.pruneForChapter(content.getAllImageUrls().toSet())

            preferencesManager.batchUpdateLastRead(content.url, effectiveLibraryItemId)

            effectiveLibraryItemId?.let { id ->
                libraryRepository.markAsCurrentlyReading(id)
                // Write the full anchor set, not just `progress`. Leaving the other fields
                // `Unchanged` lets `progress` drift away from `lastScrollPosition` /
                // `lastReadIndex` / `lastReadElementKey`, which on relaunch produces the
                // "seek bar 89%, reader at top" bug — the seek bar reads `progress` but
                // the percent-fallback restore reads `lastScrollPosition`.
                libraryRepository.saveProgressExplicitAsync(
                    itemId = id,
                    currentChapter = chapterTitle,
                    progress = FieldUpdate.Set(initialPosition.scrollProgress),
                    currentChapterUrl = FieldUpdate.Set(content.url),
                    lastScrollProgress = FieldUpdate.Set(initialPosition.scrollPosition),
                    lastReadIndex = FieldUpdate.Set(initialPosition.scrollIndex),
                    lastReadElementKey = FieldUpdate.Set(initialPosition.scrollElementKey),
                    lastReadOffsetFraction = FieldUpdate.Set(initialPosition.scrollOffsetFraction)
                )
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
        val pendingFraction = _uiState.value.restoreOffsetFraction
            .takeIf { it >= 0f }
        progressController.onUserInteraction(
            uiTargetScrollPosition = _uiState.value.targetScrollPosition,
            uiPendingRestoreOffsetFraction = pendingFraction,
            updateUiState = { targetScrollPosition, pendingRestoreOffsetFraction ->
                updateState {
                    it.copy(
                        targetScrollPosition = targetScrollPosition,
                        restoreOffsetFraction = pendingRestoreOffsetFraction
                            ?: io.aatricks.easyreader.data.model.FRACTION_UNKNOWN
                    )
                }
            }
        )
    }

    suspend fun persistLifecycleProgress(): Unit {
        val currentChapterUrl = _uiState.value.content?.url ?: return
        val content = _uiState.value.content ?: return
        progressController.cancelProgressUpdate()
        val latest = currentPersistedSnapshot()
        val shouldSnapToTop = !progressController.hasUserInteractedSinceLoad &&
            latest.scrollProgress == 0 &&
            !latest.isPreciseRestore &&
            latest.scrollIndex == 0 &&
            latest.scrollElementKey.isBlank()

        if (shouldSnapToTop) {
            val itemId = currentLibraryItemId
            val existing = itemId?.let { libraryRepository.getItemById(it) }
            val sameChapter = existing != null &&
                existing.currentChapterUrl.ifBlank { existing.url } == currentChapterUrl
            if (existing != null && sameChapter && existing.progress > 0) {
                Log.d(
                    TAG,
                    "persistLifecycleProgress skip snap-to-top url=${io.aatricks.easyreader.util.UrlSanitizer.sanitize(currentChapterUrl)} dbProgress=${existing.progress}"
                )
                return
            }
        }

        if (!shouldSnapToTop && !progressController.isSnapshotPersistable(content, latest)) {
            Log.d(
                TAG,
                "persistLifecycleProgress skip unstable url=${io.aatricks.easyreader.util.UrlSanitizer.sanitize(currentChapterUrl)} firstVisibleItemSize=${latest.firstVisibleItemSize} fraction=${latest.scrollOffsetFraction}"
            )
            return
        }

        Log.d(
            TAG,
            "persistLifecycleProgress url=${io.aatricks.easyreader.util.UrlSanitizer.sanitize(currentChapterUrl)} index=${latest.scrollIndex} fraction=${latest.scrollOffsetFraction} firstVisibleItemSize=${latest.firstVisibleItemSize}"
        )

        updateReadingProgress(
            progress = if (shouldSnapToTop) 0 else latest.scrollProgress,
            scrollPosition = if (shouldSnapToTop) 0f else latest.scrollPosition,
            index = if (shouldSnapToTop) 0 else latest.scrollIndex,
            elementKey = if (shouldSnapToTop) "" else latest.scrollElementKey,
            offsetFraction = if (shouldSnapToTop) 0f else latest.scrollOffsetFraction,
            currentChapterUrl = currentChapterUrl
        )
    }

    private fun currentPersistedSnapshot(): ReaderProgressState {
        return progressController.currentPersistedSnapshot()
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offsetFraction: Float,
        elementKey: String,
        canScrollForward: Boolean = true,
        firstVisibleItemSize: Int = 0
    ): Unit {
        progressController.updateScrollPosition(
            scrollOffset = scrollOffset,
            maxScrollOffset = maxScrollOffset,
            viewportHeight = viewportHeight,
            index = index,
            offsetFraction = offsetFraction,
            elementKey = elementKey,
            content = _uiState.value.content,
            canScrollForward = canScrollForward,
            firstVisibleItemSize = firstVisibleItemSize
        )
        lastRawScrollOffset = scrollOffset
    }

    suspend fun updateReadingProgress(
        progress: Int,
        scrollPosition: Float? = null,
        index: Int? = null,
        elementKey: String? = null,
        offsetFraction: Float? = null,
        currentChapterUrl: String? = null,
        forcePersist: Boolean = false
    ): Unit {
        progressController.updateReadingProgress(
            progress = progress,
            scrollPosition = scrollPosition,
            index = index,
            elementKey = elementKey,
            offsetFraction = offsetFraction,
            currentChapterUrl = currentChapterUrl,
            content = _uiState.value.content,
            forcePersist = forcePersist
        )
    }

    fun clearError(): Unit {
        updateState { it.copy(error = null) }
    }

    fun retryLoad(): Unit {
        val url = _uiState.value.lastAttemptedUrl ?: _uiState.value.content?.url
        val fromBottom = _uiState.value.lastFromBottom
        val isExplicit = _uiState.value.lastIsExplicitNavigation
        url?.let {
            loadContent(
                it,
                currentLibraryItemId,
                fromBottom = fromBottom,
                isExplicitNavigation = isExplicit,
                resetWebStateBeforeLoad = true
            )
        }
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

    fun downloadVisibleImage(imageUrl: String, pageUrl: String): Unit {
        if (!imageUrl.startsWith("http")) return

        viewModelScope.launch {
            runCatching { contentRepository.downloadAndCacheImage(imageUrl, pageUrl) }
        }
    }

    fun repairVisibleImage(imageUrl: String, pageUrl: String): Unit {
        if (!imageUrl.startsWith("http")) return

        viewModelScope.launch {
            repairVisibleImageNow(imageUrl, pageUrl)
        }
    }

    suspend fun repairVisibleImageNow(imageUrl: String, pageUrl: String): Boolean {
        if (!imageUrl.startsWith("http")) return false

        return runCatching {
            contentRepository.invalidateCachedMediaFile(imageUrl, pageUrl)
            libraryRepository.getItemByUrl(pageUrl)
                ?.takeIf { it.isDownloaded }
                ?.let { libraryRepository.markDownloaded(it.id, false) }
            contentRepository.downloadAndCacheImage(imageUrl, pageUrl) != null
        }.onFailure { e ->
            Log.w(TAG, "repairVisibleImage failed", e)
        }.getOrDefault(false)
    }

    fun clearCache(url: String): Unit {
        viewModelScope.launch { runCatching { contentRepository.clearCache(url) } }
    }

    fun clearAllCache(): Unit {
        viewModelScope.launch {
            runCatching { contentRepository.clearAllCache() }
                .onFailure { e ->
                    Log.w(TAG, "clearAllCache failed", e)
                    val friendly = io.aatricks.easyreader.util.ErrorMessages.fromRaw(e.message)
                    updateState { it.copy(error = "${friendly.title}: ${friendly.body}") }
                }
        }
    }

    suspend fun getCacheSize(): Long = contentRepository.getCacheSize()

    suspend fun getDownloadsSize(): Long = contentRepository.getDownloadsSize()

    fun saveScrollPosition(position: Float): Unit {
        progressController.syncProgressState(
            progressController.progressState.value.copy(
                scrollPosition = position,
                scrollProgress = position.toInt()
            )
        )
    }

    fun getScrollPosition(): Float = progressController.progressState.value.scrollPosition

    fun latestProgressSnapshot(): ReaderProgressState = progressController.progressState.value

    fun seekToProgress(progress: Float): Unit {
        val targetPercent = progress.coerceIn(0f, 100f)
        val content = _uiState.value.content
        val totalItems = content?.paragraphs?.size ?: 0

        val preciseItemIndex = (targetPercent / 100f) * (totalItems - 1).coerceAtLeast(0)
        val roughIndex = preciseItemIndex.toInt().coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        val targetFraction = if (targetPercent == 100f) 1f else 0f
        val targetElementKey = content?.paragraphs?.getOrNull(roughIndex)
            ?.let { stableContentElementKey(content.url, roughIndex, it) }
            ?: ""

        updateState {
            it.copy(
                scrollPosition = targetPercent,
                scrollProgress = targetPercent.toInt(),
                scrollIndex = roughIndex,
                restoreElementKey = targetElementKey,
                restoreOffsetFraction = targetFraction,
                isPreciseRestore = false,
                seekTrigger = System.currentTimeMillis(),
                targetScrollPosition = if (targetPercent == 100f) 100f else null
            )
        }
        syncProgressState(
            ReaderProgressState(
                scrollPosition = targetPercent,
                scrollProgress = targetPercent.toInt(),
                scrollIndex = roughIndex,
                scrollElementKey = targetElementKey,
                scrollOffsetFraction = targetFraction,
                isPreciseRestore = false,
                firstVisibleItemSize = PAGED_POSITION_ITEM_SIZE_PX,
                seekTrigger = System.currentTimeMillis(),
                targetScrollPosition = if (targetPercent == 100f) 100f else null
            )
        )

        // Seek-bar drag is explicit user intent. Mark it before scheduling the write so
        // the restore loop triggered by seekTrigger does not later suppress saves, and
        // pass forcePersist=true to bypass the upstream-layout-stability gate (which
        // would otherwise reject seeks into chapters with unmeasured images).
        progressController.markUserDragged()

        viewModelScope.launch {
            updateReadingProgress(
                progress = targetPercent.toInt(),
                scrollPosition = targetPercent,
                index = roughIndex,
                elementKey = targetElementKey,
                offsetFraction = targetFraction,
                forcePersist = true
            )
        }
    }

    override fun onCleared(): Unit {
        val content = _uiState.value.content
        val progressToPersist = currentPersistedSnapshot()
        val chapterUrl = content?.url

        if (chapterUrl != null && progressController.isSnapshotPersistable(content, progressToPersist)) {
            libraryRepository.saveProgressExplicitAsync(
                itemId = currentLibraryItemId ?: "",
                currentChapter = "",
                progress = FieldUpdate.Set(progressToPersist.scrollProgress),
                currentChapterUrl = FieldUpdate.Set(chapterUrl),
                lastScrollProgress = FieldUpdate.Set(progressToPersist.scrollPosition),
                lastReadIndex = FieldUpdate.Set(progressToPersist.scrollIndex),
                lastReadElementKey = FieldUpdate.Set(progressToPersist.scrollElementKey),
                lastReadOffsetFraction = FieldUpdate.Set(progressToPersist.scrollOffsetFraction)
            )
        }

        super.onCleared()
        closeContent(content)
    }

    fun toggleControls(): Unit = updateState { it.copy(showControls = !it.showControls) }

    fun hideControls(): Unit {
        if (!_uiState.value.showControls) return
        updateState { it.copy(showControls = false) }
    }

    fun toggleReadingMode(): Unit {
        val newMode = !uiState.value.isPagedMode
        setPagedMode(newMode)
    }

    fun setPagedMode(isPagedMode: Boolean): Unit {
        val newMode = isPagedMode
        val current = uiState.value.isPagedMode
        updateState {
            it.copy(
                isPagedMode = newMode,
                toastMessage = if (current != newMode)
                    if (newMode) "Layout: Paged" else "Layout: Scroll"
                else it.toastMessage
            )
        }
        currentLibraryItemId?.let { id ->
            viewModelScope.launch {
                libraryRepository.updateReadingMode(id, if (newMode) ReadingMode.PAGED else ReadingMode.VERTICAL)
            }
        }
    }

    fun toggleRtl(): Unit = setRtl(!uiState.value.isRtl)

    fun setRtl(isRtl: Boolean): Unit = updateState {
        if (it.isRtl == isRtl) it
        else it.copy(isRtl = isRtl, toastMessage = if (isRtl) "Direction: RTL" else "Direction: LTR")
    }

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
            val cached = chapterListCache.load(baseUrl, sourceName)
            if (cached != null && cached.chapters.isNotEmpty()) {
                val normalizedCached = normalizeChapterList(cached.chapters)
                applyFullChapterList(normalizedCached)
                if (chapterListCache.isFresh(cached)) return@launch
            }

            runCatching {
                if (cached == null) updateState { it.copy(isChaptersLoading = true) }
                val details = exploreRepository.getNovelDetails(baseUrl, sourceName)
                val normalizedChapters = normalizeChapterList(details?.chapters.orEmpty())
                if (details != null && normalizedChapters.isNotEmpty()) {
                    chapterListCache.save(baseUrl, sourceName, normalizedChapters)
                    applyFullChapterList(normalizedChapters)
                } else if (cached == null) {
                    updateState { it.copy(isChaptersLoading = false) }
                }
            }.onFailure {
                if (cached == null) updateState { it.copy(isChaptersLoading = false) }
            }
        }
    }

    private suspend fun applyFullChapterList(normalizedChapters: List<ChapterInfo>) {
        updateState { state ->
            // Now that the authoritative list is loaded, correct the header label if the current
            // chapter's URL-derived number was wrong (e.g. Novelight's opaque /book/chapter/{id}).
            val resolvedTitle = state.content?.url
                ?.let { resolveChapterLabelFromList(it, state.chapterTitle, normalizedChapters) }
            state.copy(
                fullChapterList = normalizedChapters,
                isChaptersLoading = false,
                isFullChapterListLoaded = true,
                chapterTitle = resolvedTitle ?: state.chapterTitle
            )
        }
        updateNavigationUrls()
        healLibraryItemForChapterList(normalizedChapters)
    }

    private suspend fun healLibraryItemForChapterList(normalizedChapters: List<ChapterInfo>) {
        val id = currentLibraryItemId ?: return
        val item = libraryRepository.getItemById(id) ?: return
        val newCount = normalizedChapters.size
        // Novelight stores the /book/chapter/{id} id as the current-chapter number; once the real
        // list is loaded, rewrite it so the library card shows the right chapter.
        val healedChapter = healCurrentChapterLabel(
            item.currentChapter, _uiState.value.content?.url, normalizedChapters
        )
        val countChanged = item.totalChapters != newCount
        if (countChanged || healedChapter != null) {
            val markerChapterNumber = item.resolvedChapterNumber()
            val wasCaughtUp = countChanged &&
                newCount > item.totalChapters &&
                item.totalChapters > 0 &&
                markerChapterNumber != null &&
                markerChapterNumber >= item.totalChapters.toDouble() &&
                item.hasFinishedProgress()
            libraryRepository.updateItem(
                item.copy(
                    totalChapters = if (countChanged) newCount else item.totalChapters,
                    currentChapter = healedChapter ?: item.currentChapter,
                    hasUpdates = if (wasCaughtUp) true else item.hasUpdates
                )
            )
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
