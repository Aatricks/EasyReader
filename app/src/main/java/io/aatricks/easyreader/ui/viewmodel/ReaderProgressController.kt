package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.ui.util.normalizeRestoreOffset
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Controller for managing reading progress persistence, restoration, and calculation.
 * Extracted from ReaderViewModel to focus on progress logic and facilitate testing.
 */
class ReaderProgressController(
    private val libraryRepository: LibraryRepository,
    private val scope: CoroutineScope
) {
    private val _progressState = MutableStateFlow(ReaderProgressState())
    val progressState: StateFlow<ReaderProgressState> = _progressState.asStateFlow()

    // Current library item ID being read
    var currentLibraryItemId: String? = null

    // Suppress auto navigation when restoring a saved position until user interacts
    var suppressAutoNavUntilUserInteraction: Boolean = false
    var restoredScrollPercent: Float = 0f
    var hasUserInteractedSinceLoad: Boolean = false
    var restoredProgressSnapshot: ReaderProgressState? = null

    // Track last raw scroll offset (pixels) to detect actual user gesture direction
    private var lastRawScrollOffset: Float = -1f
    private var lastReportedIndex: Int = -1
    private var lastReportedOffsetPx: Int = -1
    private var lastReportedProgress: Float = -1f

    // Debounce progress updates to reduce jitter
    private var progressUpdateJob: Job? = null

    companion object {
        private const val TAG = "ReaderProgress"
        private const val MIN_SCROLL_OFFSET_DELTA_PX = 8
        private const val MIN_SCROLL_PROGRESS_DELTA_PERCENT = 0.35f
        private const val MIN_STABLE_MANHWA_ITEM_SIZE_PX = 96
    }

    fun syncProgressState(
        scrollPosition: Float,
        scrollProgress: Int,
        scrollIndex: Int,
        scrollOffset: Int,
        scrollOffsetFraction: Float? = _progressState.value.scrollOffsetFraction,
        firstVisibleItemSize: Int = _progressState.value.firstVisibleItemSize,
        seekTrigger: Long = _progressState.value.seekTrigger,
        targetScrollPosition: Float? = _progressState.value.targetScrollPosition
    ) {
        _progressState.value = ReaderProgressState(
            scrollPosition = scrollPosition,
            scrollProgress = scrollProgress,
            scrollIndex = scrollIndex,
            scrollOffset = scrollOffset,
            scrollOffsetFraction = scrollOffsetFraction,
            firstVisibleItemSize = firstVisibleItemSize,
            seekTrigger = seekTrigger,
            targetScrollPosition = targetScrollPosition
        )
    }

    fun calculateInitialScroll(
        content: ChapterContent,
        libraryItem: LibraryItem?,
        fromBottom: Boolean,
        isExplicitNavigation: Boolean
    ): ScrollState {
        return if (libraryItem != null && !isExplicitNavigation) {
            val shouldRestoreAtTop = libraryItem.progress == 0
            restoredScrollPercent = if (shouldRestoreAtTop) 0f else libraryItem.lastScrollPosition
            suppressAutoNavUntilUserInteraction = true
            hasUserInteractedSinceLoad = false
            val scrollState = ScrollState(
                index = if (shouldRestoreAtTop) 0 else libraryItem.lastReadIndex,
                position = if (shouldRestoreAtTop) 0f else libraryItem.lastScrollPosition,
                progress = if (shouldRestoreAtTop) 0 else libraryItem.progress,
                offset = when {
                    shouldRestoreAtTop -> 0
                    libraryItem.lastReadOffsetFraction != null -> 0
                    else -> libraryItem.lastReadOffset
                },
                offsetFraction = if (shouldRestoreAtTop) 0f else libraryItem.lastReadOffsetFraction,
                targetPosition = if (shouldRestoreAtTop) 0f else libraryItem.lastScrollPosition
            )
            restoredProgressSnapshot = ReaderProgressState(
                scrollPosition = if (shouldRestoreAtTop) 0f else libraryItem.lastScrollPosition,
                scrollProgress = if (shouldRestoreAtTop) 0 else libraryItem.progress,
                scrollIndex = if (shouldRestoreAtTop) 0 else libraryItem.lastReadIndex,
                scrollOffset = if (shouldRestoreAtTop) 0 else libraryItem.lastReadOffset,
                scrollOffsetFraction = if (shouldRestoreAtTop) 0f else libraryItem.lastReadOffsetFraction,
                firstVisibleItemSize = 0,
                seekTrigger = 0L,
                targetScrollPosition = if (shouldRestoreAtTop) 0f else libraryItem.lastScrollPosition
            )
            scrollState
        } else {
            restoredScrollPercent = if (fromBottom) 100f else 0f
            suppressAutoNavUntilUserInteraction = true
            hasUserInteractedSinceLoad = false
            ScrollState(
                index = if (fromBottom) (content.paragraphs.size - 1).coerceAtLeast(0) else 0,
                position = if (fromBottom) 100f else 0f,
                progress = if (fromBottom) 100 else 0,
                offset = if (fromBottom) 10000000 else 0,
                offsetFraction = if (fromBottom) 1f else 0f,
                targetPosition = if (fromBottom) 100f else 0f
            ).also { restoredProgressSnapshot = it.toProgressState() }
        }
    }

    fun onUserInteraction(
        uiTargetScrollPosition: Float?,
        uiPendingRestoreOffsetFraction: Float?,
        updateUiState: (targetScrollPosition: Float?, pendingRestoreOffsetFraction: Float?) -> Unit
    ) {
        val progressState = _progressState.value
        val requiresInteractionCleanup = !hasUserInteractedSinceLoad ||
            suppressAutoNavUntilUserInteraction ||
            restoredProgressSnapshot != null ||
            uiTargetScrollPosition != null ||
            uiPendingRestoreOffsetFraction != null ||
            progressState.targetScrollPosition != null

        if (!requiresInteractionCleanup) return

        hasUserInteractedSinceLoad = true
        suppressAutoNavUntilUserInteraction = false
        restoredProgressSnapshot = null
        
        var nextUiTargetScrollPosition = uiTargetScrollPosition
        var nextUiPendingRestoreOffsetFraction = uiPendingRestoreOffsetFraction
        
        if (uiTargetScrollPosition != null || uiPendingRestoreOffsetFraction != null) {
            nextUiTargetScrollPosition = null
            nextUiPendingRestoreOffsetFraction = null
        }
        
        updateUiState(nextUiTargetScrollPosition, nextUiPendingRestoreOffsetFraction)
        
        if (progressState.targetScrollPosition != null) {
            _progressState.update { it.copy(targetScrollPosition = null) }
        }
    }

    suspend fun saveCurrentProgress(content: ChapterContent?) {
        val prevItemId = currentLibraryItemId ?: return
        val prevContent = content ?: return
        val progressSnapshot = currentPersistedSnapshot()

        if (isPlaceholderAtCurrentPosition(prevContent, progressSnapshot.scrollIndex)) return

        runCatching {
            libraryRepository.updateProgressExplicit(
                itemId = prevItemId,
                currentChapter = "",
                progress = FieldUpdate.Set(progressSnapshot.scrollProgress),
                currentChapterUrl = FieldUpdate.Set(prevContent.url),
                lastScrollProgress = FieldUpdate.Set(progressSnapshot.scrollPosition),
                lastReadIndex = FieldUpdate.Set(progressSnapshot.scrollIndex),
                lastReadOffset = FieldUpdate.Set(progressSnapshot.scrollOffset),
                lastReadOffsetFraction = progressSnapshot.scrollOffsetFraction?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Clear
            )
        }
    }

    fun currentPersistedSnapshot(): ReaderProgressState {
        return if (suppressAutoNavUntilUserInteraction && !hasUserInteractedSinceLoad) {
            restoredProgressSnapshot ?: _progressState.value
        } else {
            _progressState.value
        }
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offset: Int,
        content: ChapterContent?,
        canScrollForward: Boolean = true,
        firstVisibleItemSize: Int = 0
    ) {
        val deltaRaw = if (lastRawScrollOffset < 0f) 0f else scrollOffset - lastRawScrollOffset
        // Note: isScrollingDown is not used here but kept for logic consistency if needed later
        // val isScrollingDown = deltaRaw > 0f

        val progress = when {
            !canScrollForward -> 100f
            maxScrollOffset > viewportHeight -> ((scrollOffset / (maxScrollOffset - viewportHeight)) * 100f).coerceIn(
                0f,
                100f
            )

            maxScrollOffset > 0 -> 100f
            else -> 0f
        }

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

        if (suppressAutoNavUntilUserInteraction) {
            lastRawScrollOffset = scrollOffset
            return
        }

        val offsetFraction = normalizeRestoreOffset(offset, firstVisibleItemSize)

        _progressState.value = _progressState.value.copy(
            scrollPosition = progress,
            scrollProgress = progressInt,
            scrollIndex = index,
            scrollOffset = offset,
            scrollOffsetFraction = offsetFraction,
            firstVisibleItemSize = firstVisibleItemSize
        )

        if (shouldSkipPersistForUnstableManhwaSample(content, index, firstVisibleItemSize)) {
            lastRawScrollOffset = scrollOffset
            return
        }

        progressUpdateJob?.cancel()
        progressUpdateJob = scope.launch {
            delay(100)

            if (progressInt >= 0) {
                updateReadingProgress(
                    progress = progressInt,
                    scrollPosition = progress,
                    index = index,
                    offset = offset,
                    offsetFraction = offsetFraction,
                    content = content
                )
            }
            lastRawScrollOffset = scrollOffset
        }
    }

    suspend fun updateReadingProgress(
        progress: Int,
        scrollPosition: Float? = null,
        index: Int? = null,
        offset: Int? = null,
        offsetFraction: Float? = null,
        currentChapterUrl: String? = null,
        content: ChapterContent? = null
    ) {
        val itemId = currentLibraryItemId ?: return
        runCatching {
            val resolvedChapterUrl = currentChapterUrl ?: content?.url ?: ""
            val latest = currentPersistedSnapshot()
            val lastScroll = scrollPosition ?: latest.scrollPosition
            val lastIndex = index ?: latest.scrollIndex
            val lastOffset = offset ?: latest.scrollOffset
            val lastOffsetFraction = offsetFraction ?: latest.scrollOffsetFraction

            if (isPlaceholderAtCurrentPosition(content, lastIndex)) return@runCatching

            val currentElement = content?.paragraphs?.getOrNull(lastIndex)
            val elementAnchor = when (currentElement) {
                is ContentElement.Image -> currentElement.url
                is ContentElement.ImageGroup -> currentElement.images.firstOrNull()?.url
                else -> null
            }
            Log.d(
                TAG,
                "saveProgress url=${io.aatricks.easyreader.util.UrlSanitizer.sanitize(resolvedChapterUrl)} index=$lastIndex offset=$lastOffset offsetFraction=$lastOffsetFraction firstVisibleItemSize=${latest.firstVisibleItemSize} anchor=${if (elementAnchor != null) "<elt>" else "null"}"
            )

            libraryRepository.updateProgressExplicit(
                itemId = itemId,
                currentChapter = "",
                progress = FieldUpdate.Set(progress),
                currentChapterUrl = FieldUpdate.Set(resolvedChapterUrl),
                lastScrollProgress = FieldUpdate.Set(lastScroll),
                lastReadIndex = FieldUpdate.Set(lastIndex),
                lastReadOffset = FieldUpdate.Set(lastOffset),
                lastReadOffsetFraction = lastOffsetFraction?.let { FieldUpdate.Set(it) } ?: FieldUpdate.Clear
            )
        }
    }

    private fun isPlaceholderAtCurrentPosition(content: ChapterContent?, index: Int? = null): Boolean {
        val lastIndex = index ?: _progressState.value.scrollIndex
        val paragraphs = content?.paragraphs ?: return false
        val currentItem = paragraphs.getOrNull(lastIndex)
        return currentItem is ContentElement.Placeholder || 
               (currentItem is ContentElement.Text && currentItem.content.startsWith("Loading page"))
    }

    fun resetState() {
        _progressState.value = ReaderProgressState()
        currentLibraryItemId = null
        hasUserInteractedSinceLoad = false
        restoredProgressSnapshot = null
        lastRawScrollOffset = -1f
        lastReportedIndex = -1
        lastReportedOffsetPx = -1
        lastReportedProgress = -1f
        progressUpdateJob?.cancel()
    }

    fun cancelProgressUpdate() {
        progressUpdateJob?.cancel()
    }

    private fun shouldSkipPersistForUnstableManhwaSample(
        content: ChapterContent?,
        index: Int,
        firstVisibleItemSize: Int
    ): Boolean {
        if (content == null || firstVisibleItemSize >= MIN_STABLE_MANHWA_ITEM_SIZE_PX) return false

        val isLongStrip = isLongStripContent(content)
        if (!isLongStrip) return false

        return when (content.paragraphs.getOrNull(index)) {
            is ContentElement.Image, is ContentElement.ImageGroup -> true
            else -> false
        }
    }

    private fun isLongStripContent(content: ChapterContent): Boolean {
        val isManga = content.url.contains("manga", ignoreCase = true) &&
            !content.url.contains("manhwa", ignoreCase = true) &&
            !content.url.contains("webtoon", ignoreCase = true)
        if (isManga) return false

        return content.url.contains("manhwa", ignoreCase = true) ||
            content.url.contains("webtoon", ignoreCase = true) ||
            (content.getImageCount() > content.getTextCount() && content.getImageCount() > 2)
    }
}

data class ReaderProgressState(
    val scrollPosition: Float = 0f,
    val scrollProgress: Int = 0,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val scrollOffsetFraction: Float? = null,
    val firstVisibleItemSize: Int = 0,
    val seekTrigger: Long = 0L,
    val targetScrollPosition: Float? = null
)

data class ScrollState(
    val index: Int,
    val position: Float,
    val progress: Int,
    val offset: Int,
    val offsetFraction: Float?,
    val targetPosition: Float? = null
)

internal fun ScrollState.toProgressState(): ReaderProgressState = ReaderProgressState(
    scrollPosition = position,
    scrollProgress = progress,
    scrollIndex = index,
    scrollOffset = offset,
    scrollOffsetFraction = offsetFraction,
    firstVisibleItemSize = 0,
    seekTrigger = 0L,
    targetScrollPosition = targetPosition
)
