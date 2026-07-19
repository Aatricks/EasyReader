package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.model.LIBRARY_FINISHED_PROGRESS_THRESHOLD
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Reading-position controller. Single source of truth for the unified position model:
 *
 *  - `scrollPosition` / `scrollProgress`: % of the chapter (0..100) — UI display + last-resort restore.
 *  - `scrollElementKey`: stable per-item anchor (image URL, paragraph hash). Preferred over index.
 *  - `scrollIndex`: itemsIndexed fallback when the element key can't be located.
 *  - `scrollOffsetFraction`: intra-item fraction (0..1), or `FRACTION_UNKNOWN` (-1f) = unmeasured.
 *  - `firstVisibleItemSize`: stability witness; placeholder-sized items must not pollute the DB.
 */
class ReaderProgressController(
    private val libraryRepository: LibraryRepository,
    private val scope: CoroutineScope,
    private val sessionTracker: ReadingSessionTracker? = null
) {

    private val _progressState = MutableStateFlow(ReaderProgressState())
    val progressState: StateFlow<ReaderProgressState> = _progressState.asStateFlow()

    var currentLibraryItemId: String? = null

    /**
     * Restore gating — the flags below encode three semi-independent concerns; they are NOT a
     * single linear phase. Transitions:
     *
     *   entry (calculateInitialPosition / beginRestore):
     *       restoreInProgress = true                     // layout still reflowing as images decode
     *       userHasDragged    = false                    // no confirmed touch yet
     *       suppressAutoNavUntilUserInteraction = true   // (calculateInitialPosition only)
     *       hasUserInteractedSinceLoad = false
     *
     *   restore loop finishes (markRestoreDone):
     *       restoreInProgress = false                    // ONLY this clears; suppress/drag unchanged
     *
     *   confirmed user drag / interaction (markUserDragged / onUserInteraction):
     *       userHasDragged = true; hasUserInteractedSinceLoad = true
     *       suppressAutoNavUntilUserInteraction = false; restoreInProgress = false
     *
     * Note: beginRestore() deliberately re-arms restoreInProgress + userHasDragged for a
     * mid-flight seek WITHOUT touching suppressAutoNav — the axes are independent.
     *
     * Write-gating consequences:
     *   - suppressAutoNavUntilUserInteraction: in-memory state still updates (UI/seek bar stay
     *     live) but NO DB writes and NO auto-nav until the user acts.
     *   - restoreInProgress && !userHasDragged: skip DB writes — the position is a mid-reflow
     *     restore landing, not user intent.
     *   - hasUserInteractedSinceLoad flips on programmatic scroll too, so self-heal / smoke
     *     checks gate on userHasDragged (the stricter flag) instead.
     */
    var suppressAutoNavUntilUserInteraction: Boolean = false
    var restoredScrollPercent: Float = 0f
    var hasUserInteractedSinceLoad: Boolean = false
    var restoredProgressSnapshot: ReaderProgressState? = null

    /**
     * True only after a confirmed user drag/press on the reader content. Distinct from
     * `hasUserInteractedSinceLoad`, which also flips on programmatic scroll-position changes
     * (e.g. the restore loop's own `scrollToItem`). Self-heal / smoke-check logic gates on
     * this flag instead — yanking a user mid-gesture is worse than tolerating a small drift,
     * but reflow-induced scroll updates should never disable the self-heal.
     */
    var userHasDragged: Boolean = false

    /**
     * True from `calculateInitialPosition` until the restore loop in the UI layer calls
     * `markRestoreDone()`. While set (and the user has not yet dragged), DB writes from
     * scroll snapshots are suppressed because the layout is still reflowing as images decode.
     */
    var restoreInProgress: Boolean = false

    /**
     * Monotonic restore-request counter. Bumped ONLY at genuine restore chokepoints
     * (`calculateInitialPosition` = a new chapter load, and `seekToProgress` via
     * `requestRestore()`). A bare recomposition — e.g. returning from the full library screen,
     * which disposes and recreates the reader composition — does NOT bump it. The UI restore
     * path uses this to distinguish "replay the frozen load/seek anchor" from "re-apply the
     * live scrolled position", which is what stops a library round-trip from clobbering progress.
     */
    var restoreRequestId: Long = 0L
        private set

    fun requestRestore() {
        restoreRequestId++
    }

    private var lastRawScrollOffset: Float = -1f
    private var lastReportedIndex: Int = -1
    private var lastReportedFractionMillis: Int = -1
    private var lastReportedProgress: Float = -1f

    private var progressUpdateJob: Job? = null
    private var lastUpdateTime: Long = 0L
    private var pendingUpdate: PendingProgressUpdate? = null

    private data class PendingProgressUpdate(
        val progress: Int,
        val scrollPosition: Float,
        val index: Int,
        val elementKey: String,
        val offsetFraction: Float,
        val content: ChapterContent?,
        val forcePersist: Boolean
    )

    companion object {
        private const val TAG = "ReaderProgress"
        // 0.5% of an item. Below this an intra-item fraction change is pixel-rounding noise, not
        // real movement — skip the DB write.
        private const val MIN_SCROLL_FRACTION_DELTA_PERMILLE = 5
        // Minimum chapter-percent change worth persisting. With the fraction/index guards this
        // collapses a scroll gesture's hundreds of samples into a handful of writes.
        private const val MIN_SCROLL_PROGRESS_DELTA_PERCENT = 0.35f
        // An item smaller than this is almost certainly still a placeholder (real content items are
        // taller). Positions measured against it are meaningless and must not pollute the saved row.
        const val MIN_STABLE_ITEM_SIZE_PX = 96
        // Sentinel "item size" for paged mode and terminal end-of-chapter samples, where there is no
        // real intra-item measurement but the position is explicit user intent. Bypasses the
        // placeholder-size stability gate in isSnapshotPersistable.
        const val PAGED_POSITION_ITEM_SIZE_PX = Int.MAX_VALUE
        // If a saved row claims index 0 with zero fraction but its percent says we are more than this
        // far into the chapter, it is a partial-write ghost row: ignore the index and use the percent
        // fallback.
        private const val SUSPICIOUS_ZERO_INDEX_PERCENT_THRESHOLD = 5f
    }

    fun syncProgressState(state: ReaderProgressState) {
        _progressState.value = state
    }

    /**
     * Compute the initial position when entering a chapter. Returns `ReaderProgressState` directly
     * (the old `ScrollState` intermediary was a strict duplicate). Resolution order on restore:
     *
     *  1. If `libraryItem.progress == 0` → top.
     *  2. Locate `lastReadElementKey` inside the current chapter's elements → use that index.
     *  3. If `lastReadIndex` points to a valid element with a usable fraction → use it.
     *  4. Otherwise derive a coarse index from `lastScrollPosition` (the percent). This handles
     *     legacy rows (missing key, FRACTION_UNKNOWN) and chapter-reparses where the element layout
     *     shifted.
     */
    fun calculateInitialPosition(
        content: ChapterContent,
        libraryItem: LibraryItem?,
        fromBottom: Boolean,
        isExplicitNavigation: Boolean
    ): ReaderProgressState {
        requestRestore()
        suppressAutoNavUntilUserInteraction = true
        hasUserInteractedSinceLoad = false
        userHasDragged = false
        restoreInProgress = true

        if (libraryItem == null || isExplicitNavigation) {
            restoredScrollPercent = if (fromBottom) 100f else 0f
            val lastIndex = (content.paragraphs.size - 1).coerceAtLeast(0)
            val state = ReaderProgressState(
                scrollPosition = if (fromBottom) 100f else 0f,
                scrollProgress = if (fromBottom) 100 else 0,
                scrollIndex = if (fromBottom) lastIndex else 0,
                scrollElementKey = "",
                scrollOffsetFraction = if (fromBottom) 1f else 0f,
                firstVisibleItemSize = 0,
                seekTrigger = 0L,
                targetScrollPosition = if (fromBottom) 100f else 0f
            )
            restoredProgressSnapshot = state
            return state
        }

        val shouldRestoreAtTop = libraryItem.progress == 0 && !libraryItem.hasRestorablePosition()
        // `progress` (Int 0..100) and `lastScrollPosition` (Float) are redundant percent sources
        // that can diverge in old rows (partial writes). Trust the larger non-zero value so a
        // surviving field still drives the seek bar and the percent fallback in agreement.
        val effectivePercent = kotlin.math.max(libraryItem.lastScrollPosition, libraryItem.progress.toFloat())
            .coerceIn(0f, 100f)
        restoredScrollPercent = if (shouldRestoreAtTop) 0f else effectivePercent

        val resolved = if (shouldRestoreAtTop) {
            ResolvedPosition(index = 0, elementKey = "", fraction = 0f, isPrecise = false)
        } else {
            resolveRestoredIndex(content, libraryItem, effectivePercent)
        }

        val resolvedProgress = if (shouldRestoreAtTop) {
            0
        } else {
            kotlin.math.max(libraryItem.progress, effectivePercent.toInt())
        }
        val state = ReaderProgressState(
            scrollPosition = if (shouldRestoreAtTop) 0f else effectivePercent,
            scrollProgress = resolvedProgress,
            scrollIndex = resolved.index,
            scrollElementKey = resolved.elementKey,
            scrollOffsetFraction = resolved.fraction,
            isPreciseRestore = resolved.isPrecise,
            firstVisibleItemSize = 0,
            seekTrigger = 0L,
            targetScrollPosition = if (shouldRestoreAtTop) 0f else effectivePercent
        )
        restoredProgressSnapshot = state
        return state
    }

    private fun resolveRestoredIndex(
        content: ChapterContent,
        libraryItem: LibraryItem,
        effectivePercent: Float = libraryItem.lastScrollPosition
    ): ResolvedPosition {
        val totalItems = content.paragraphs.size
        if (totalItems <= 0) {
            return ResolvedPosition(index = 0, elementKey = "", fraction = 0f, isPrecise = false)
        }
        val lastItemIndex = totalItems - 1

        return resolveByElementKey(content, libraryItem, lastItemIndex)
            ?: resolveBySavedIndex(content, libraryItem, lastItemIndex, effectivePercent)
            ?: resolveByPercent(content, lastItemIndex, effectivePercent)
    }

    // (2) Stable element-key anchor — survives reorderings, splits, item-count drift.
    // When duplicate keys exist (e.g. site logo image URL repeated), pick the occurrence
    // closest to `lastReadIndex` so a header duplicate doesn't drag the reader to the top.
    private fun resolveByElementKey(
        content: ChapterContent,
        libraryItem: LibraryItem,
        lastItemIndex: Int
    ): ResolvedPosition? {
        val savedKey = libraryItem.lastReadElementKey
        val anchorIndex = libraryItem.lastReadIndex.coerceIn(0, lastItemIndex)
        var bestIdx = -1
        var bestDistance = Int.MAX_VALUE
        if (savedKey.isNotEmpty()) {
            content.paragraphs.forEachIndexed { idx, element ->
                if (stableContentElementKey(content.url, idx, element) == savedKey) {
                    val distance = kotlin.math.abs(idx - anchorIndex)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIdx = idx
                    }
                }
            }
        }
        return if (bestIdx < 0) {
            null
        } else {
            ResolvedPosition(
                index = bestIdx,
                elementKey = savedKey,
                fraction = libraryItem.lastReadOffsetFraction.takeIf { it >= 0f } ?: 0f,
                isPrecise = true
            )
        }
    }

    // (3) Saved index with usable fraction. Suspicious case: `lastReadIndex==0` with a
    // zero fraction while the percent says we're far in — this looks like a partial-write
    // ghost row, so we fall through to the percent fallback instead of trusting index 0.
    private fun resolveBySavedIndex(
        content: ChapterContent,
        libraryItem: LibraryItem,
        lastItemIndex: Int,
        effectivePercent: Float
    ): ResolvedPosition? {
        val savedIndex = libraryItem.lastReadIndex.coerceIn(0, lastItemIndex)
        val hasUsableFraction = libraryItem.lastReadOffsetFraction >= 0f
        val hasSavedPosition = libraryItem.lastReadIndex > 0 || hasUsableFraction
        val suspiciousZeroIndex = libraryItem.lastReadIndex == 0 &&
            libraryItem.lastReadOffsetFraction <= 0f &&
            effectivePercent > SUSPICIOUS_ZERO_INDEX_PERCENT_THRESHOLD
        if (!hasSavedPosition || suspiciousZeroIndex) return null
        val refreshedKey = content.paragraphs.getOrNull(savedIndex)
            ?.let { stableContentElementKey(content.url, savedIndex, it) }
            ?: ""
        return ResolvedPosition(
            index = savedIndex,
            elementKey = refreshedKey,
            fraction = if (hasUsableFraction) libraryItem.lastReadOffsetFraction else 0f,
            isPrecise = true
        )
    }

    // (4) Percent fallback. Legacy rows with missing key/fraction land here. Use
    // `effectivePercent` (max of `progress` and `lastScrollPosition`) so a stale-zero
    // `lastScrollPosition` doesn't drag the reader to the top while `progress` says 89%.
    private fun resolveByPercent(
        content: ChapterContent,
        lastItemIndex: Int,
        effectivePercent: Float
    ): ResolvedPosition {
        val percent = effectivePercent.coerceIn(0f, 100f) / 100f
        val derivedIndex = (percent * lastItemIndex).toInt().coerceIn(0, lastItemIndex)
        val refreshedKey = content.paragraphs.getOrNull(derivedIndex)
            ?.let { stableContentElementKey(content.url, derivedIndex, it) }
            ?: ""
        return ResolvedPosition(index = derivedIndex, elementKey = refreshedKey, fraction = 0f, isPrecise = false)
    }

    fun markUserDragged() {
        userHasDragged = true
        hasUserInteractedSinceLoad = true
        suppressAutoNavUntilUserInteraction = false
        restoredProgressSnapshot = null
        restoreInProgress = false
    }

    fun markRestoreDone() {
        restoreInProgress = false
    }

    /**
     * Called by the UI restore LaunchedEffect at entry. Mirrors [calculateInitialPosition]'s
     * flag setup so that mid-flight UI-driven restores (e.g. seek-bar drags that bump
     * `seekTrigger`) re-arm the gating even though they don't go through
     * [calculateInitialPosition]. Without this, a second restore proceeds with stale
     * `restoreInProgress=false`, letting mid-decode saves poison the just-seeked position.
     */
    fun beginRestore() {
        restoreInProgress = true
        userHasDragged = false
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

        // Callers are paths that only fire on confirmed user input (nested scroll with
        // touchSlop, seek bar, nav buttons). Treat as a drag for restore-gating purposes.
        hasUserInteractedSinceLoad = true
        userHasDragged = true
        suppressAutoNavUntilUserInteraction = false
        restoreInProgress = false
        restoredProgressSnapshot = null
        sessionTracker?.onInteraction()

        val nextUiTargetScrollPosition = if (uiTargetScrollPosition != null) null else uiTargetScrollPosition
        val nextUiPendingRestoreOffsetFraction = if (uiPendingRestoreOffsetFraction != null) null else uiPendingRestoreOffsetFraction

        updateUiState(nextUiTargetScrollPosition, nextUiPendingRestoreOffsetFraction)

        if (progressState.targetScrollPosition != null) {
            _progressState.update { it.copy(targetScrollPosition = null) }
        }
    }

    suspend fun saveCurrentProgress(content: ChapterContent?) {
        val prevItemId = currentLibraryItemId ?: return
        val prevContent = content ?: return
        val snapshot = currentPersistedSnapshot()

        if (!isSnapshotPersistable(prevContent, snapshot)) return

        runCatching {
            libraryRepository.updateProgressExplicit(
                itemId = prevItemId,
                currentChapter = "",
                progress = FieldUpdate.Set(snapshot.scrollProgress),
                currentChapterUrl = FieldUpdate.Set(prevContent.url),
                lastScrollProgress = FieldUpdate.Set(snapshot.scrollPosition),
                lastReadIndex = FieldUpdate.Set(snapshot.scrollIndex),
                lastReadElementKey = FieldUpdate.Set(snapshot.scrollElementKey),
                lastReadOffsetFraction = FieldUpdate.Set(snapshot.scrollOffsetFraction)
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

    /**
     * Guard the DB write paths: refuse to persist measurements taken against placeholder-sized
     * items, and refuse to persist an unknown fraction. Stops the placeholder-pollution pipeline
     * that caused "saved progress but no anchor" rows under the old code.
     */
    fun isSnapshotPersistable(content: ChapterContent?, snapshot: ReaderProgressState): Boolean {
        if (content == null || content.paragraphs.isEmpty()) return false
        if (snapshot.scrollOffsetFraction < 0f) return false
        if (snapshot.firstVisibleItemSize in 1 until MIN_STABLE_ITEM_SIZE_PX) {
            // Item measured but at placeholder size — fraction is meaningless. Drop write.
            return false
        }
        if (isPlaceholderAtCurrentPosition(content, snapshot.scrollIndex)) return false
        // Upstream-layout-stability gate intentionally NOT enforced here. Anchor fields
        // (index + fraction + elementKey) are precise as long as the *current* item is
        // measured, and the persisted percent is approximate by design (pixel-weighted
        // estimate from visible items). Holding writes back because some image far above
        // hasn't reported its dimensions yet leaves the DB stuck at a stale percent —
        // exactly the "lands higher than I was" bug.
        return true
    }

    fun updateScrollPosition(
        scrollOffset: Float,
        maxScrollOffset: Float,
        viewportHeight: Float,
        index: Int,
        offsetFraction: Float,
        elementKey: String,
        content: ChapterContent?,
        canScrollForward: Boolean = true,
        firstVisibleItemSize: Int = 0
    ) {
        val progress = when {
            !canScrollForward -> 100f
            maxScrollOffset > viewportHeight -> ((scrollOffset / (maxScrollOffset - viewportHeight)) * 100f).coerceIn(0f, 100f)
            maxScrollOffset > 0 -> 100f
            else -> 0f
        }
        val progressInt = progress.toInt()
        val fractionPermille = (offsetFraction.coerceIn(0f, 1f) * 1000f).toInt()

        val isMicroDelta = index == lastReportedIndex &&
            kotlin.math.abs(fractionPermille - lastReportedFractionMillis) < MIN_SCROLL_FRACTION_DELTA_PERMILLE &&
            kotlin.math.abs(progress - lastReportedProgress) < MIN_SCROLL_PROGRESS_DELTA_PERCENT

        if (isMicroDelta) {
            lastRawScrollOffset = scrollOffset
            return
        }
        lastReportedIndex = index
        lastReportedFractionMillis = fractionPermille
        lastReportedProgress = progress

        if (suppressAutoNavUntilUserInteraction) {
            lastRawScrollOffset = scrollOffset
            return
        }

        // While the restore loop is still settling layout — and the user has not yet
        // touched the screen — skip DB writes entirely. The in-memory state still updates
        // below so the seek bar/UI stay live, but we refuse to overwrite the saved row
        // with positions produced by image-decode-induced reflow.
        if (restoreInProgress && !userHasDragged) {
            lastRawScrollOffset = scrollOffset
            return
        }

        sessionTracker?.onInteraction()

        val isStable = firstVisibleItemSize >= MIN_STABLE_ITEM_SIZE_PX
        val isTerminal = !canScrollForward
        val effectiveFraction = when {
            isTerminal -> offsetFraction.coerceIn(0f, 1f)
            isStable -> offsetFraction.coerceIn(0f, 1f)
            else -> FRACTION_UNKNOWN
        }

        val nextState = _progressState.value.copy(
            scrollPosition = progress,
            scrollProgress = progressInt,
            scrollIndex = index,
            scrollElementKey = if (isStable || isTerminal) elementKey else _progressState.value.scrollElementKey,
            scrollOffsetFraction = effectiveFraction,
            firstVisibleItemSize = firstVisibleItemSize
        )
        _progressState.value = nextState

        // Terminal end-of-chapter samples are explicit intent: persist them via the
        // PAGED_POSITION_ITEM_SIZE_PX sentinel so isSnapshotPersistable bypasses the
        // upstream-layout-stability gate (which near the end almost always fails because
        // earlier images haven't been measured yet).
        val persistSnapshot = if (isTerminal) {
            nextState.copy(firstVisibleItemSize = PAGED_POSITION_ITEM_SIZE_PX)
        } else {
            nextState
        }

        if ((!isStable && !isTerminal) || !isSnapshotPersistable(content, persistSnapshot)) {
            lastRawScrollOffset = scrollOffset
            return
        }

        lastUpdateTime = System.currentTimeMillis()
        pendingUpdate = PendingProgressUpdate(
            progress = progressInt,
            scrollPosition = progress,
            index = index,
            elementKey = elementKey,
            offsetFraction = effectiveFraction,
            content = content,
            forcePersist = isTerminal
        )

        if (progressUpdateJob?.isActive != true) {
            progressUpdateJob = scope.launch {
                var update = pendingUpdate
                if (update != null) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateTime
                    if (elapsed < 100) {
                        delay(100 - elapsed)
                    }
                    update = pendingUpdate
                    if (update != null) {
                        if (update.progress >= 0) {
                            updateReadingProgress(
                                progress = update.progress,
                                scrollPosition = update.scrollPosition,
                                index = update.index,
                                elementKey = update.elementKey,
                                offsetFraction = update.offsetFraction,
                                content = update.content,
                                forcePersist = update.forcePersist
                            )
                        }
                        pendingUpdate = null
                    }
                }
                lastRawScrollOffset = scrollOffset
            }
        }
    }

    suspend fun updateReadingProgress(
        progress: Int,
        scrollPosition: Float? = null,
        index: Int? = null,
        elementKey: String? = null,
        offsetFraction: Float? = null,
        currentChapterUrl: String? = null,
        content: ChapterContent? = null,
        forcePersist: Boolean = false
    ) {
        val itemId = currentLibraryItemId ?: return
        runCatching {
            val resolvedChapterUrl = currentChapterUrl ?: content?.url ?: ""
            val latest = currentPersistedSnapshot()
            val lastScroll = scrollPosition ?: latest.scrollPosition
            val lastIndex = index ?: latest.scrollIndex
            val lastElementKey = elementKey ?: latest.scrollElementKey
            val lastFraction = offsetFraction ?: latest.scrollOffsetFraction
            val snapshot = latest.copy(
                scrollPosition = lastScroll,
                scrollProgress = progress,
                scrollIndex = lastIndex,
                scrollElementKey = lastElementKey,
                scrollOffsetFraction = lastFraction,
                firstVisibleItemSize = if (forcePersist) PAGED_POSITION_ITEM_SIZE_PX else latest.firstVisibleItemSize
            )

            if (isPlaceholderAtCurrentPosition(content, lastIndex)) return@runCatching
            if (!isSnapshotPersistable(content, snapshot)) return@runCatching

            Log.d(
                TAG,
                "saveProgress url=${io.aatricks.easyreader.util.UrlSanitizer.sanitize(resolvedChapterUrl)} index=$lastIndex elementKey=${if (lastElementKey.isNotEmpty()) "<set>" else "<empty>"} fraction=$lastFraction firstVisibleItemSize=${latest.firstVisibleItemSize}"
            )

            if (progress >= LIBRARY_FINISHED_PROGRESS_THRESHOLD) {
                sessionTracker?.onChapterCompleted(resolvedChapterUrl)
            }

            libraryRepository.updateProgressExplicit(
                itemId = itemId,
                currentChapter = "",
                progress = FieldUpdate.Set(progress),
                currentChapterUrl = FieldUpdate.Set(resolvedChapterUrl),
                lastScrollProgress = FieldUpdate.Set(lastScroll),
                lastReadIndex = FieldUpdate.Set(lastIndex),
                lastReadElementKey = FieldUpdate.Set(lastElementKey),
                lastReadOffsetFraction = FieldUpdate.Set(lastFraction)
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
        userHasDragged = false
        restoreInProgress = false
        restoredProgressSnapshot = null
        lastRawScrollOffset = -1f
        lastReportedIndex = -1
        lastReportedFractionMillis = -1
        lastReportedProgress = -1f
        progressUpdateJob?.cancel()
        pendingUpdate = null
    }

    fun cancelProgressUpdate() {
        progressUpdateJob?.cancel()
        pendingUpdate = null
    }
}

data class ReaderProgressState(
    val scrollPosition: Float = 0f,
    val scrollProgress: Int = 0,
    val scrollIndex: Int = 0,
    val scrollElementKey: String = "",
    val scrollOffsetFraction: Float = FRACTION_UNKNOWN,
    val isPreciseRestore: Boolean = false,
    val firstVisibleItemSize: Int = 0,
    val seekTrigger: Long = 0L,
    val targetScrollPosition: Float? = null
)

private data class ResolvedPosition(
    val index: Int,
    val elementKey: String,
    val fraction: Float,
    val isPrecise: Boolean
)

private fun LibraryItem.hasRestorablePosition(): Boolean =
    lastReadElementKey.isNotEmpty() ||
        lastReadIndex > 0 ||
        lastReadOffsetFraction >= 0f ||
        lastScrollPosition > 0f ||
        progress > 0

/**
 * Lifted from the Compose renderer so non-UI code can resolve element anchors. Keep this
 * deterministic and pure — both the writer (during scroll) and the reader (during restore) must
 * agree on the same key for the same logical element.
 */
internal fun stableContentElementKey(pageUrl: String, index: Int, element: ContentElement): String {
    return when (element) {
        is ContentElement.Image -> "img:${element.url}"
        is ContentElement.ImageGroup -> "group:${element.images.joinToString("|") { it.url }}"
        is ContentElement.Text -> "txt:$pageUrl:$index:${element.content.take(64).hashCode()}"
        is ContentElement.Placeholder -> "placeholder:$pageUrl:$index:${element.text}"
        is ContentElement.PageContent -> "page:$pageUrl:$index"
    }
}
