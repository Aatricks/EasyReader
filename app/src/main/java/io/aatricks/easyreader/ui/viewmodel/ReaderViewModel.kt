package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
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
import io.aatricks.easyreader.util.areChapterUrlsMatching
import io.aatricks.easyreader.util.healCurrentChapterLabel
import io.aatricks.easyreader.util.inferBaseNovelUrlFromUrl
import io.aatricks.easyreader.util.inferSourceNameFromUrl
import io.aatricks.easyreader.util.matchChapterIndex
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.util.resolveChapterLabelFromList
import io.aatricks.easyreader.util.TextUtils
import io.aatricks.easyreader.util.UrlSecurity
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.PAGED_POSITION_ITEM_SIZE_PX
import io.aatricks.easyreader.util.FieldUpdate
import io.aatricks.easyreader.util.computeDownloadCleanup
import io.aatricks.easyreader.data.local.ReaderSettingsSnapshot
import io.aatricks.easyreader.data.repository.ReaderCaches
import io.aatricks.easyreader.util.ErrorMessages
import io.aatricks.easyreader.util.UrlSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
    private val readerCaches: ReaderCaches,
    private val sessionTracker: ReadingSessionTracker
) : BaseViewModel<ReaderViewModel.ReaderUiState>(ReaderUiState()) {
    private val progressController = ReaderProgressController(libraryRepository, viewModelScope, sessionTracker)
    val progressState: StateFlow<ReaderProgressState> = progressController.progressState

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val MIN_READER_BRIGHTNESS = 0.1f
        private const val MAX_READER_BRIGHTNESS = 1.0f
        private val DOUBLE_NEWLINE_REGEX = Regex("""\n\s*\n""")
        internal var contentDimensionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
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

    // Survives recomposition because ReaderViewModel is activity-scoped. Tracks the last
    // restore-request id the UI restore path already consumed, so a bare recomposition — the
    // reader being disposed and recreated on a library round-trip, with no new load/seek —
    // is told to re-apply the live scrolled position instead of replaying the frozen load/seek
    // anchor from uiState, which is what silently clobbered progress.
    private var lastConsumedRestoreId: Long = 0L

    /**
     * Called once per `runScrollRestore` entry. Decides whether this restore is a GENUINE
     * load/seek (replay the frozen `uiState` anchor — unchanged behavior) or a BARE
     * RECOMPOSITION (re-apply the live `progressState` position), marks the request consumed,
     * and returns the anchor to apply. The decision lives here — not in the composable — so it
     * is unit-testable.
     */
    fun consumeRestoreAnchor(): RestoreAnchor {
        val pendingId = progressController.restoreRequestId
        val isGenuine = pendingId != lastConsumedRestoreId
        lastConsumedRestoreId = pendingId
        return if (isGenuine) {
            val ui = _uiState.value
            RestoreAnchor(
                elementKey = ui.restoreElementKey,
                scrollIndex = ui.scrollIndex,
                offsetFraction = ui.restoreOffsetFraction,
                scrollPosition = ui.scrollPosition,
                isPreciseRestore = ui.isPreciseRestore,
                targetScrollPosition = ui.targetScrollPosition,
                isLiveSource = false
            )
        } else {
            val live = progressController.progressState.value
            RestoreAnchor(
                elementKey = live.scrollElementKey,
                scrollIndex = live.scrollIndex,
                offsetFraction = live.scrollOffsetFraction,
                scrollPosition = live.scrollPosition,
                isPreciseRestore = live.isPreciseRestore,
                // Hard-null: never replay a from-bottom / seek-to-end one-shot on a bare return.
                // That one-shot is only meaningful for a genuine load/seek (which always take the
                // genuine branch above). The live scrollIndex/fraction/percent already encode a
                // real end-of-chapter landing if that is where the user actually is.
                targetScrollPosition = null,
                isLiveSource = true
            )
        }
    }

    // Resolved intrinsic dimensions keyed by image URL, one Compose State per URL. A
    // ReaderImageView subscribes to its own url's State, so a write only recomposes that one
    // image — and an item scrolled away and back is sized correctly on its FIRST composition
    // (no collapse to the loading placeholder + relayout). This is what keeps fast up/down
    // dragging smooth; the debounced `content` rebuild below stays only for persistence /
    // restore math.
    private val imageDimensionManager = ImageDimensionManager(
        scope = viewModelScope,
        imageDimensionCache = readerCaches.imageDimensionCache,
        applyContentDimensions = ::enqueueCurrentContentImageDimensions,
    )
    private val pendingContentDimensionUpdates = LinkedHashMap<String, Pair<Int, Int>>()
    private var contentDimensionTransformJob: Job? = null

    fun imageDimensionState(imageUrl: String): androidx.compose.runtime.State<Pair<Int, Int>?> =
        imageDimensionManager.dimensionState(imageUrl)

    fun persistImageDimensions(imageUrl: String, width: Int, height: Int) =
        imageDimensionManager.persistImageDimensions(imageUrl, width, height)

    private fun enqueueCurrentContentImageDimensions(updates: Map<String, Pair<Int, Int>>) {
        pendingContentDimensionUpdates.putAll(updates)
        contentDimensionTransformJob?.cancel()
        contentDimensionTransformJob = viewModelScope.launch {
            while (pendingContentDimensionUpdates.isNotEmpty()) {
                val source = uiState.value.content ?: return@launch
                val batch = pendingContentDimensionUpdates.toMap()
                val transformed = withContext(contentDimensionDispatcher) {
                    applyResolvedImageDimensions(source, batch)
                }
                var applied = false
                updateState { state ->
                    if (state.content !== source) {
                        state
                    } else {
                        applied = true
                        if (transformed === source) state else state.copy(content = transformed)
                    }
                }
                if (applied) {
                    batch.forEach { (url, dimensions) ->
                        pendingContentDimensionUpdates.remove(url, dimensions)
                    }
                }
            }
        }
    }

    // Track if we're explicitly navigating (not restoring from library)
    private var isExplicitNavigation: Boolean = false

    // Track last raw scroll offset (pixels) to detect actual user gesture direction
    private var lastRawScrollOffset: Float = -1f

    // Job for tracking content loading
    private var loadJob: Job? = null

    private fun applyReaderSettings(snapshot: ReaderSettingsSnapshot) {
        updateState {
            it.copy(
                fontSize = snapshot.fontSize,
                lineHeight = snapshot.lineHeight,
                fontFamily = snapshot.fontFamily,
                margins = snapshot.margins,
                verticalMargins = snapshot.verticalMargins,
                paragraphSpacing = snapshot.paragraphSpacing,
                brightness = snapshot.brightness,
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
        val verticalMargins: Int,
        val paragraphSpacing: Float,
        val brightness: Float,
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

    /**
     * The anchor `runScrollRestore` applies. `isLiveSource` distinguishes the genuine load/seek
     * path (frozen `uiState` fields) from the bare-recomposition path (live `progressState`).
     * See [consumeRestoreAnchor].
     */
    data class RestoreAnchor(
        val elementKey: String,
        val scrollIndex: Int,
        val offsetFraction: Float,
        val scrollPosition: Float,
        val isPreciseRestore: Boolean,
        val targetScrollPosition: Float?,
        val isLiveSource: Boolean
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
        val restoreOffsetFraction: Float = FRACTION_UNKNOWN,
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
        val verticalMargins: Int = 0,
        val paragraphSpacing: Float = 1.0f,
        val brightness: Float = 1.0f,
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
                verticalMargins = verticalMargins,
                paragraphSpacing = paragraphSpacing,
                brightness = brightness,
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
    }

    private fun syncProgressState(state: ReaderProgressState) {
        progressController.syncProgressState(state)
    }

    fun requestOpenFile(uri: String) {
        updateState { it.copy(pendingFileConfirmationUri = uri, showFileConfirmationDialog = true) }
    }

    fun dismissFileConfirmation() {
        updateState { it.copy(pendingFileConfirmationUri = null, showFileConfirmationDialog = false) }
    }

    fun requestOpenUrl(url: String) {
        viewModelScope.launch {
            if (UrlSecurity.isSafeUrl(url)) {
                updateState { it.copy(pendingExternalUrl = url, showExternalUrlConfirmation = true) }
            } else {
                updateState { it.copy(toastMessage = "Blocked unsafe or invalid URL") }
            }
        }
    }

    fun confirmExternalUrl() {
        val url = _uiState.value.pendingExternalUrl ?: return
        updateState { it.copy(pendingExternalUrl = null, showExternalUrlConfirmation = false) }
        loadContent(url)
    }

    fun cancelExternalUrl() {
        updateState { it.copy(pendingExternalUrl = null, showExternalUrlConfirmation = false) }
    }

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
        if (preferencesManager.fontFamily == newFamily) return
        preferencesManager.fontFamily = newFamily
        updateState { it.copy(fontFamily = newFamily, toastMessage = "Font: $newFamily") }
    }

    fun updateMargins(newMargins: Int) {
        val margins = newMargins.coerceIn(4, 64)
        preferencesManager.margins = margins
        updateState { it.copy(margins = margins) }
    }

    fun updateVerticalMargins(newMargins: Int) {
        val verticalMargins = newMargins.coerceIn(0, 160)
        preferencesManager.verticalMargins = verticalMargins
        updateState { it.copy(verticalMargins = verticalMargins) }
    }

    fun updateParagraphSpacing(newSpacing: Float) {
        val spacing = newSpacing.coerceIn(0.0f, 3.0f)
        preferencesManager.paragraphSpacing = spacing
        updateState { it.copy(paragraphSpacing = spacing) }
    }

    fun updateBrightness(newBrightness: Float) {
        val brightness = newBrightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
        preferencesManager.brightness = brightness
        updateState { it.copy(brightness = brightness) }
    }

    fun updateReaderTheme(newTheme: ReaderTheme) {
        if (preferencesManager.readerTheme == newTheme.name) return
        preferencesManager.readerTheme = newTheme.name
        val label = newTheme.name.lowercase().replaceFirstChar { it.uppercase() }
        updateState { it.copy(readerTheme = newTheme, toastMessage = "Theme: $label") }
    }

    fun clearToast() {
        updateState { it.copy(toastMessage = null) }
    }

    fun openChapterFromStart(
        url: String,
        libraryItemId: String? = null,
        fromBottom: Boolean = false,
        isSilent: Boolean = false
    ) = loadContent(
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
    ) {
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
    ) {
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

    private fun handleEpubUrl(
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

    private suspend fun saveCurrentProgress() {
        progressController.saveCurrentProgress(_uiState.value.content)
    }

    private suspend fun handleLoadSuccess(
        result: ContentResult.Success,
        libraryItemId: String?,
        fromBottom: Boolean
    ) {
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

        val effectiveBaseNovelUrl = libraryItem?.baseNovelUrl?.ifBlank { null }
            ?: inferBaseNovelUrlFromUrl(content.url)
        val effectiveSourceName = libraryItem?.sourceName?.ifBlank { null }
            ?: inferSourceNameFromUrl(content.url)

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
                baseNovelUrl = effectiveBaseNovelUrl,
                sourceName = effectiveSourceName,
                isPagedMode = isPaged,
                fullChapterList = currentFullList
            )
        }
        syncProgressState(initialPosition)
        sessionTracker.start(baseTitle.ifBlank { novelName })

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
            if (effectiveBaseNovelUrl.isNotBlank() && effectiveSourceName.isNotBlank()) {
                loadFullChapterList(effectiveBaseNovelUrl, effectiveSourceName)
            }
            if (item.baseTitle.isBlank() || item.baseNovelUrl.isBlank() || item.sourceName.isBlank()) {
                libraryRepository.healNovelMetadata(
                    itemId = item.id,
                    baseTitle = item.baseTitle.ifBlank { baseTitle },
                    baseNovelUrl = item.baseNovelUrl.ifBlank { effectiveBaseNovelUrl },
                    sourceName = item.sourceName.ifBlank { effectiveSourceName }
                )
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

    private fun handleLoadError(result: ContentResult.Error) {
        updateState { it.copy(isLoading = false, isNavigating = false, error = result.message) }
    }

    private fun getBaseTitle(content: ChapterContent, libraryItem: LibraryItem?): String {
        return libraryItem?.baseTitle?.ifBlank { null }
            ?: libraryItem?.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) }
            ?: content.title?.let { TextUtils.extractBaseTitle(it, ContentType.WEB) }
            ?: ""
    }

    fun navigateToNextChapter() = navigateToAdjacentChapter(isNext = true)
    fun navigateToPreviousChapter(fromBottom: Boolean = false) =
        navigateToAdjacentChapter(isNext = false, fromBottom = fromBottom)

    private fun navigateToAdjacentChapter(isNext: Boolean, fromBottom: Boolean = false) {
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
                sourceName = currentItem.sourceName,
                coverImageUrl = currentItem.coverImageUrl
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
    ) {
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
            sessionTracker.start(baseTitle.ifBlank { novelName })

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

    fun onUserInteraction() {
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
                            ?: FRACTION_UNKNOWN
                    )
                }
            }
        )
    }

    suspend fun persistLifecycleProgress() {
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
                    "persistLifecycleProgress skip snap-to-top " +
                        "url=${UrlSanitizer.sanitize(currentChapterUrl)} dbProgress=${existing.progress}"
                )
                return
            }
        }

        if (!shouldSnapToTop && !progressController.isSnapshotPersistable(content, latest)) {
            Log.d(
                TAG,
                "persistLifecycleProgress skip unstable " +
                    "url=${UrlSanitizer.sanitize(currentChapterUrl)} " +
                    "firstVisibleItemSize=${latest.firstVisibleItemSize} " +
                    "fraction=${latest.scrollOffsetFraction}"
            )
            return
        }

        Log.d(
            TAG,
            "persistLifecycleProgress " +
                "url=${UrlSanitizer.sanitize(currentChapterUrl)} " +
                "index=${latest.scrollIndex} " +
                "fraction=${latest.scrollOffsetFraction} " +
                "firstVisibleItemSize=${latest.firstVisibleItemSize}"
        )

        updateReadingProgress(
            progress = if (shouldSnapToTop) 0 else latest.scrollProgress,
            scrollPosition = if (shouldSnapToTop) 0f else latest.scrollPosition,
            index = if (shouldSnapToTop) 0 else latest.scrollIndex,
            elementKey = if (shouldSnapToTop) "" else latest.scrollElementKey,
            offsetFraction = if (shouldSnapToTop) 0f else latest.scrollOffsetFraction,
            currentChapterUrl = currentChapterUrl
        )
        sessionTracker.stop()
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
    ) {
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
    ) {
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

    fun clearError() {
        updateState { it.copy(error = null) }
    }

    fun retryLoad() {
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

    fun prefetchVisibleImage(imageUrl: String, pageUrl: String) {
        if (!imageUrl.startsWith("http")) return

        viewModelScope.launch {
            runCatching { contentRepository.warmImage(imageUrl, pageUrl) }
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

    fun clearAllCache() {
        viewModelScope.launch {
            runCatching { contentRepository.clearAllCache() }
                .onFailure { e ->
                    Log.w(TAG, "clearAllCache failed", e)
                    val friendly = ErrorMessages.fromRaw(e.message)
                    updateState { it.copy(error = "${friendly.title}: ${friendly.body}") }
                }
        }
    }

    suspend fun getCacheSize(): Long = contentRepository.getCacheSize()

    suspend fun getDownloadsSize(): Long = contentRepository.getDownloadsSize()

    fun seekToProgress(progress: Float) {
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
        // requestRestore() makes the seekTrigger-driven runScrollRestore read the fresh
        // uiState seek anchor (genuine branch) rather than the live progressState.
        progressController.requestRestore()
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

    override fun onCleared() {
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

        sessionTracker.stop()

        super.onCleared()
        closeContent(content)
    }


    fun toggleControls() = updateState { it.copy(showControls = !it.showControls) }

    fun hideControls() {
        if (!_uiState.value.showControls) return
        updateState { it.copy(showControls = false) }
    }

    fun toggleReadingMode() {
        val newMode = !uiState.value.isPagedMode
        setPagedMode(newMode)
    }

    fun setPagedMode(isPagedMode: Boolean) {
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

    fun setRtl(isRtl: Boolean) = updateState {
        if (it.isRtl == isRtl) it
        else it.copy(isRtl = isRtl, toastMessage = if (isRtl) "Direction: RTL" else "Direction: LTR")
    }

    fun navigateToChapter(url: String, title: String) {
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

    fun loadFullChapterList(baseUrl: String, sourceName: String) {
        viewModelScope.launch {
            val cached = readerCaches.chapterListCache.load(baseUrl, sourceName)
            if (cached != null && cached.chapters.isNotEmpty()) {
                val normalizedCached = normalizeChapterList(cached.chapters)
                applyFullChapterList(normalizedCached)
                if (readerCaches.chapterListCache.isFresh(cached)) return@launch
            }

            runCatching {
                if (cached == null) updateState { it.copy(isChaptersLoading = true) }
                val details = exploreRepository.getNovelDetails(baseUrl, sourceName)
                val normalizedChapters = normalizeChapterList(details?.chapters.orEmpty())
                if (details != null && normalizedChapters.isNotEmpty()) {
                    readerCaches.chapterListCache.save(baseUrl, sourceName, normalizedChapters)
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
            val wasCaughtUp = isCatchUpUpdate(countChanged, newCount, item)
            // Targeted metadata write (not a whole-row REPLACE) so a concurrent progress write
            // between the getItemById above and here is never clobbered. healedChapter is
            // already null unless the label changed; markHasUpdates only ever sets the flag.
            libraryRepository.healChapterMetadata(
                itemId = id,
                totalChapters = if (countChanged) newCount else null,
                currentChapter = healedChapter,
                markHasUpdates = wasCaughtUp
            )
        }
        if (item.baseTitle.isBlank() || item.baseNovelUrl.isBlank() || item.sourceName.isBlank()) {
            libraryRepository.healNovelMetadata(
                itemId = id,
                baseTitle = item.baseTitle.ifBlank { _uiState.value.baseTitle },
                baseNovelUrl = item.baseNovelUrl.ifBlank { _uiState.value.baseNovelUrl },
                sourceName = item.sourceName.ifBlank { _uiState.value.sourceName }
            )
        }
    }

    private fun isCatchUpUpdate(countChanged: Boolean, newCount: Int, item: LibraryItem): Boolean {
        val markerChapterNumber = item.resolvedChapterNumber()
        return countChanged &&
            newCount > item.totalChapters &&
            item.totalChapters > 0 &&
            markerChapterNumber != null &&
            markerChapterNumber >= item.totalChapters.toDouble() &&
            item.hasFinishedProgress()
    }

    private fun updateNavigationUrls() {
        val state = _uiState.value
        if (!state.isFullChapterListLoaded) return
        val currentUrl = state.content?.url ?: return
        val list = state.fullChapterList
        if (list.isEmpty()) return

        val currentIndex = matchChapterIndex(list, currentUrl, state.chapterTitle)
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

internal fun applyResolvedImageDimensions(
    content: ChapterContent,
    updates: Map<String, Pair<Int, Int>>
): ChapterContent {
    var changed = false
    val paragraphs = content.paragraphs.map { element ->
        val resolved = element.withResolvedImageDimensions(updates)
        if (resolved !== element) changed = true
        resolved
    }
    return if (changed) content.copy(paragraphs = paragraphs) else content
}

private fun ContentElement.withResolvedImageDimensions(
    updates: Map<String, Pair<Int, Int>>
): ContentElement = when (this) {
    is ContentElement.Image -> {
        val dimensions = updates[url]
        if (dimensions != null && (width <= 0 || height <= 0)) {
            copy(width = dimensions.first, height = dimensions.second)
        } else {
            this
        }
    }

    is ContentElement.ImageGroup -> {
        val updatedImages = images.map { image ->
            val dimensions = updates[image.url]
            if (dimensions != null && (image.width <= 0 || image.height <= 0)) {
                image.copy(width = dimensions.first, height = dimensions.second)
            } else {
                image
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
