package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.FRACTION_UNKNOWN
import io.aatricks.easyreader.ui.components.TopInfoBar
import io.aatricks.easyreader.ui.components.BottomNavigationBar
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.PAGED_POSITION_ITEM_SIZE_PX
import io.aatricks.easyreader.ui.util.toFontFamily
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.MIN_STABLE_ITEM_SIZE_PX
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.stableContentElementKey
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.aatricks.easyreader.ui.components.ReaderBottomEdgeBlur
import io.aatricks.easyreader.ui.components.ReaderTopEdgeBlur
import io.aatricks.easyreader.ui.components.applyReaderEdgeBlur
import io.aatricks.easyreader.ui.components.supportsReaderEdgeBlur
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

// Post-restore smoke check: if the landed visible percent drifts more than this many
// percentage points from the saved percent, assume async image resize knocked us off and
// fall back to a percent-based scroll. ~half a screen on a typical chapter: big enough to
// ignore pixel-weighted percent noise, small enough to catch a real miss.
private const val RESTORE_PERCENT_TOLERANCE = 5f
// Debounce before re-capturing the graphics layer behind the top/bottom edge blur, so a
// burst of scroll events coalesces into a single recapture instead of one per frame.
private const val EDGE_BLUR_RECAPTURE_DEBOUNCE_MS = 200L
// Poll cadence for the watch-until-stable restore loop (~5 frames at 60Hz). Fast enough
// that re-applying scroll is imperceptible, slow enough not to busy-spin while images decode.
private const val RESTORE_STABILITY_POLL_INTERVAL_MS = 80L
// The target item must hold its position for this long before restore is considered locked
// in — guards against a mid-decode equilibrium that looks stable for only a frame or two.
private const val RESTORE_STABILITY_DURATION_MS = 300L
// Hard cap on the restore loop. If images never stabilize (slow network / decode failure)
// we accept the current position rather than spin forever.
private const val RESTORE_MAX_WAIT_MS = 3_000L
// Skip re-applying fraction unless the target item grew by at least this fraction since
// the last applied size. Without it a slow image decode produces 5–10 visible position
// hops as the LazyList re-measures intermediate sizes.
private const val RESTORE_REJUMP_THRESHOLD = 0.20f

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ContentArea(
    uiState: ReaderViewModel.ReaderUiState,
    content: ChapterContent,
    readerViewModel: ReaderViewModel,
    onLibraryClick: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
): Unit {
    val fontFamily = uiState.fontFamily.toFontFamily()

    val isManhwa = remember(content) {
        val isManhwaByUrl = content.url.contains("manhwa", ignoreCase = true) ||
            content.url.contains("webtoon", ignoreCase = true)
        isManhwaByUrl || (content.getImageCount() > content.getTextCount() && content.getImageCount() > 2)
    }

    val stableKeys = remember(content) {
        content.paragraphs.mapIndexed { idx, element ->
            stableContentElementKey(content.url, idx, element)
        }
    }

    // No init values — single restore path through LaunchedEffect below. Initial values would
    // race with the LaunchedEffect and create the "two paths, one of them silently wrong" bug.
    val listState = key(content.url) { rememberLazyListState() }

    val pagerState = key(content.url) {
        rememberPagerState(
            initialPage = uiState.scrollIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0)),
            initialPageOffsetFraction = 0f
        ) {
            content.paragraphs.size
        }
    }

    val requestedIndices = remember(content.url) { mutableSetOf<Int>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val density = LocalDensity.current

    val edgeBlurLayer = rememberGraphicsLayer()
    var edgeBlurCaptureGeneration by remember(content.url) { mutableIntStateOf(0) }
    var edgeBlurLastCaptured by remember(content.url) { mutableIntStateOf(-1) }

    LaunchedEffect(edgeBlurLayer, density) {
        if (supportsReaderEdgeBlur) {
            applyReaderEdgeBlur(edgeBlurLayer, density)
        }
    }

    LaunchedEffect(uiState.showControls) {
        if (uiState.showControls) edgeBlurCaptureGeneration++
    }

    LaunchedEffect(uiState.showControls, content.url) {
        if (!uiState.showControls) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                pagerState.currentPage
            )
        }.collectLatest {
            kotlinx.coroutines.delay(EDGE_BLUR_RECAPTURE_DEBOUNCE_MS)
            edgeBlurCaptureGeneration++
        }
    }

    // snapshotFlow instead of effect keys: reading firstVisibleItemIndex during composition
    // recomposed this whole scope — and cancelled/relaunched the effect — on every item
    // boundary crossed while scrolling. The captured `content` may be an older copy after a
    // dimension rebuild, which is fine: prefetch only reads the element urls, identical
    // across copies of the same chapter.
    LaunchedEffect(content.url, uiState.isPagedMode) {
        snapshotFlow {
            if (uiState.isPagedMode) pagerState.currentPage else listState.firstVisibleItemIndex
        }.collect { currentIndex ->
            prefetchImages(currentIndex, content, requestedIndices) { url ->
                readerViewModel.prefetchVisibleImage(url, content.url)
            }
        }
    }

    LaunchedEffect(content.url, uiState.seekTrigger) {
        runScrollRestore(content, listState, pagerState, readerViewModel, stableKeys)
    }

    // Detect genuine user drags on the LazyList. Programmatic scrollToItem calls do NOT
    // emit DragInteraction.Start, so the restore loop's own movements won't trip the flag.
    // Tap-to-toggle-controls fires PressInteraction.Press but should NOT count as "user
    // wants this position saved" — only Drag confirms that intent.
    if (!uiState.isPagedMode) {
        LaunchedEffect(listState, content.url) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    readerViewModel.markUserDragged()
                }
            }
        }
    }

    if (uiState.isPagedMode) {
        LaunchedEffect(pagerState.currentPage) {
            val totalItems = content.paragraphs.size
            val currentItem = pagerState.currentPage
            val currentKey = stableKeys.getOrNull(currentItem) ?: ""

            readerViewModel.updateScrollPosition(
                scrollOffset = currentItem.toFloat(),
                maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat(),
                viewportHeight = 0f,
                index = currentItem,
                offsetFraction = 0f,
                elementKey = currentKey,
                firstVisibleItemSize = PAGED_POSITION_ITEM_SIZE_PX
            )
        }
    } else {
        DisposableEffect(lifecycleOwner, listState, content.url, uiState.isPagedMode) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_PAUSE && event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                if (uiState.isPagedMode) return@LifecycleEventObserver

                // If restore is still running and the user has not actually scrolled the
                // content, the current listState position is the (possibly mid-reflow)
                // restore landing — never the user's intent. Persisting it here would
                // overwrite the saved row with a worse approximation of itself.
                if (readerViewModel.restoreInProgress && !readerViewModel.userHasDragged) {
                    return@LifecycleEventObserver
                }

                val snapshot = buildScrollSnapshot(listState, content, stableKeys) ?: return@LifecycleEventObserver
                readerViewModel.updateScrollPosition(
                    scrollOffset = snapshot.scrollOffset,
                    maxScrollOffset = snapshot.maxScrollOffset,
                    viewportHeight = snapshot.viewportHeightInItems,
                    index = snapshot.index,
                    offsetFraction = snapshot.offsetFraction,
                    elementKey = snapshot.elementKey,
                    canScrollForward = snapshot.canScrollForward,
                    firstVisibleItemSize = snapshot.firstVisibleItemSize
                )
                coroutineScope.launch { readerViewModel.persistLifecycleProgress() }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        LaunchedEffect(listState, content.url) {
            snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.canScrollForward
                )
            }
                .conflate()
                .collect {
                    val snapshot = buildScrollSnapshot(listState, content, stableKeys) ?: return@collect
                    readerViewModel.updateScrollPosition(
                        scrollOffset = snapshot.scrollOffset,
                        maxScrollOffset = snapshot.maxScrollOffset,
                        viewportHeight = snapshot.viewportHeightInItems,
                        index = snapshot.index,
                        offsetFraction = snapshot.offsetFraction,
                        elementKey = snapshot.elementKey,
                        canScrollForward = snapshot.canScrollForward,
                        firstVisibleItemSize = snapshot.firstVisibleItemSize
                    )
                }
        }
    }

    val threshold = remember { with(density) { 80.dp.toPx() } }
    var pullAmount by remember { mutableFloatStateOf(0f) }
    val isThresholdReached = abs(pullAmount) >= threshold

    val nestedScrollConnection = rememberReaderNestedScrollConnection(
        uiState = uiState,
        pagerState = pagerState,
        listState = listState,
        content = content,
        threshold = threshold,
        onHideControls = { readerViewModel.hideControls() },
        onUserInteraction = { readerViewModel.onUserInteraction() },
        onPullAmountChange = { pullAmount = it },
        onNavigatePrevious = { readerViewModel.navigateToPreviousChapter(fromBottom = true) },
        onNavigateNext = { readerViewModel.navigateToNextChapter() }
    )

    val readerThemeState = uiState.readerTheme
    val bgColor = readerThemeState.backgroundColor
    val textColor = readerThemeState.textColor

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .background(bgColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (uiState.showControls && edgeBlurCaptureGeneration != edgeBlurLastCaptured) {
                        edgeBlurLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = IntSize(size.width.toInt(), size.height.toInt())
                        ) {
                            this@drawWithContent.drawContent()
                        }
                        edgeBlurLastCaptured = edgeBlurCaptureGeneration
                    }
                    drawContent()
                }
        ) {
            if (uiState.isPagedMode) {
                PagedReaderView(
                    content = content,
                    pagerState = pagerState,
                    uiState = uiState,
                    fontFamily = fontFamily,
                    bgColor = bgColor,
                    textColor = textColor,
                    readerViewModel = readerViewModel,
                    isZoomable = isManhwa
                )
            } else {
                ScrollingReaderView(
                    content = content,
                    listState = listState,
                    uiState = uiState,
                    isManhwa = isManhwa,
                    fontFamily = fontFamily,
                    bgColor = bgColor,
                    textColor = textColor,
                    readerViewModel = readerViewModel
                )
            }
        }

        if (uiState.showControls) {
            ReaderTopEdgeBlur(
                graphicsLayer = edgeBlurLayer,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            ReaderBottomEdgeBlur(
                graphicsLayer = edgeBlurLayer,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopInfoBar(
                novelName = uiState.novelName,
                chapterTitle = uiState.chapterTitle,
                onLibraryClick = onLibraryClick,
                onShowChapterList = onShowChapterList,
                onShowSettings = onShowSettings
            )
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomNavigationBar(
                readerViewModel = readerViewModel,
                canNavigatePrevious = uiState.canNavigatePrevious,
                canNavigateNext = uiState.canNavigateNext,
                onPreviousClick = { readerViewModel.navigateToPreviousChapter(fromBottom = true) },
                onNextClick = { readerViewModel.navigateToNextChapter() },
                onProgressChange = { readerViewModel.seekToProgress(it) }
            )
        }

        PullToNavigateOverlay(
            pullAmount = pullAmount,
            threshold = threshold,
            isThresholdReached = isThresholdReached,
            isPagedMode = uiState.isPagedMode,
            isRtl = uiState.isRtl
        )

        if (!uiState.isPagedMode && pullAmount == 0f && !uiState.showControls) {
            val atTop = !listState.canScrollBackward
            val atBottom = !listState.canScrollForward
            EdgeNavigationHint(
                atTop = atTop && uiState.canNavigatePrevious,
                atBottom = atBottom && uiState.canNavigateNext
            )
        }
    }
}

// ─── Restore helpers ────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private suspend fun runScrollRestore(
    content: ChapterContent,
    listState: LazyListState,
    pagerState: androidx.compose.foundation.pager.PagerState,
    readerViewModel: ReaderViewModel,
    stableKeys: List<String>,
) {
    // ─── Unified restore path ───────────────────────────────────────────────
    // Single entry point handles BOTH initial load and seek-bar drags. Resolution order:
    //   1. element-key anchor (survives chapter reparse) → 2. saved index → 3. percent fallback
    // After landing, runs a smoke check: if visible % drifts > RESTORE_PERCENT_TOLERANCE from the
    // saved %, falls back to percent-based scroll (defends against async-image-resize drift).

    // Re-arm restore gating on every entry. `calculateInitialPosition` does this for
    // the first open, but seek-bar drags bump `seekTrigger` without going through it,
    // and the previous restore may have already called `markRestoreDone()`.
    readerViewModel.beginRestore()

    if (content.paragraphs.isEmpty()) {
        readerViewModel.markRestoreDone()
        return
    }

    val uiState = readerViewModel.uiState.value
    val targetIndex = resolveRestoreIndex(uiState, stableKeys)
        .coerceIn(0, content.paragraphs.lastIndex)
    val targetFraction = uiState.restoreOffsetFraction
        .takeIf { it >= 0f }
        ?.coerceIn(0f, 1f)

    // One-shot jumps: paged mode uses the page index as the whole position; from-bottom
    // navigation seeks the final item end. Anything else lands at the item and then chases
    // the intra-item fraction via the watch-until-stable loop below.
    val handledAsOneShot = when {
        uiState.isPagedMode -> {
            runCatching { pagerState.scrollToPage(targetIndex) }
            true
        }
        uiState.targetScrollPosition == 100f -> {
            runCatching { listState.scrollToItem(content.paragraphs.lastIndex, Int.MAX_VALUE) }
            true
        }
        else -> {
            // Land at the item first so the LazyList composes it. Offset comes after measurement.
            runCatching { listState.scrollToItem(targetIndex, 0) }
            false
        }
    }

    if (!handledAsOneShot) {
        awaitStableRestore(listState, targetIndex, targetFraction, readerViewModel)

        // Final percent-based smoke check. Gates on userHasDragged (not the looser
        // hasUserInteractedSinceLoad) so programmatic / reflow-induced scroll events don't
        // suppress self-heal. Runs in every imprecise case AND for precise restores with no
        // intra-item fraction — catches "landed at the wrong index but seek bar says 89%".
        if (!readerViewModel.userHasDragged &&
            shouldRunPercentRestoreFallback(
                isPreciseRestore = uiState.isPreciseRestore,
                targetFraction = targetFraction
            )
        ) {
            val visiblePercent = computeVisiblePercent(listState, content.paragraphs.size)
            val targetPercent = uiState.scrollPosition
            if (visiblePercent != null && abs(visiblePercent - targetPercent) > RESTORE_PERCENT_TOLERANCE) {
                val fallbackIndex = ((targetPercent / 100f) * content.paragraphs.lastIndex).toInt()
                    .coerceIn(0, content.paragraphs.lastIndex)
                runCatching { listState.scrollToItem(fallbackIndex, 0) }
            }
        }
    }

    readerViewModel.markRestoreDone()
}

// The watch-until-stable loop's re-fire / exit conditions are deliberately interrelated
// (index match, item-size stability, fraction chase, decode-reflow rejump). Splitting them
// further would obscure the algorithm rather than clarify it, so the essential cyclomatic
// complexity is documented inline and suppressed here.
@Suppress("CyclomaticComplexMethod")
private suspend fun awaitStableRestore(
    listState: LazyListState,
    targetIndex: Int,
    targetFraction: Float?,
    readerViewModel: ReaderViewModel,
) {
    val hasFractionToChase = targetFraction != null && targetFraction > 0f
    val chasedFraction = targetFraction?.takeIf { hasFractionToChase } ?: 0f

    // Watch-until-stable: re-apply scrollToItem every time the list state diverges from
    // the target. Critical for two reasons:
    //   1. Image-heavy chapters (manhwa): items start at placeholder size, so all of them
    //      fit the viewport and scrollToItem is a no-op (the list isn't scrollable yet).
    //      Once images decode the list grows, but firstVisibleItemIndex stays at 0 unless
    //      we re-apply the scroll.
    //   2. Intra-item fraction restore: as the target item grows from image decode reflow,
    //      the absolute pixel offset changes, so the fraction must be re-applied at the
    //      new size.
    // Bails immediately on real user drag. 3s hard cap prevents runaway loops.
    val deadline = System.currentTimeMillis() + RESTORE_MAX_WAIT_MS
    var lastAppliedSize = 0
    var lastAppliedIndex = -1
    var stableSince = 0L

    while (System.currentTimeMillis() < deadline && !readerViewModel.userHasDragged) {
        val onTargetIndex = listState.firstVisibleItemIndex == targetIndex
        val size = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == targetIndex }
            ?.size ?: 0
        val sizeStable = size >= MIN_STABLE_ITEM_SIZE_PX
        // Re-fire scroll if we're not on the target index yet, OR if the target item
        // grew enough that the intra-item offset needs recomputing.
        val sizeChangedEnough = lastAppliedSize == 0 ||
            abs(size - lastAppliedSize).toFloat() /
            lastAppliedSize.toFloat() >= RESTORE_REJUMP_THRESHOLD
        val needsScroll = !onTargetIndex || (
            hasFractionToChase && sizeStable && (
                lastAppliedIndex != targetIndex ||
                    size != lastAppliedSize && sizeChangedEnough
                )
            )
        if (needsScroll) {
            val offsetPx = if (hasFractionToChase && sizeStable) {
                (size * chasedFraction).toInt().coerceIn(0, size)
            } else 0
            runCatching { listState.scrollToItem(targetIndex, offsetPx) }
            lastAppliedSize = if (sizeStable) size else lastAppliedSize
            lastAppliedIndex = targetIndex
            stableSince = 0L
        } else if (onTargetIndex && (!hasFractionToChase || sizeStable)) {
            // Locked in: either no fraction to chase (any landing on target is fine), or
            // fraction applied at a stable size. Confirm stability over a short window
            // before exiting so a mid-decode equilibrium doesn't fool us.
            if (stableSince == 0L) stableSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - stableSince >= RESTORE_STABILITY_DURATION_MS) break
        }
        kotlinx.coroutines.delay(RESTORE_STABILITY_POLL_INTERVAL_MS)
    }
}

private fun resolveRestoreIndex(
    uiState: ReaderViewModel.ReaderUiState,
    stableKeys: List<String>
): Int {
    val key = uiState.restoreElementKey
    if (key.isNotEmpty()) {
        val idx = stableKeys.indexOf(key)
        if (idx >= 0) return idx
    }
    return uiState.scrollIndex
}

private fun computeVisiblePercent(listState: LazyListState, totalItems: Int): Float? {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstItem = visibleItems.firstOrNull()
    if (totalItems <= 0 || firstItem == null || firstItem.size <= 0) {
        return null
    }
    val viewportPx = layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
    val avgItemSizePx = if (visibleItems.isEmpty()) 1f else {
        var sum = 0f
        for (i in 0 until visibleItems.size) {
            sum += visibleItems[i].size
        }
        (sum / visibleItems.size).coerceAtLeast(1f)
    }
    val totalContentPx = totalItems.toFloat() * avgItemSizePx
    val pixelsBeforeFirst = firstItem.index.toFloat() * avgItemSizePx
    val currentPixelOffset = pixelsBeforeFirst + listState.firstVisibleItemScrollOffset.toFloat()
    val scrollablePx = (totalContentPx - viewportPx).coerceAtLeast(1f)
    return ((currentPixelOffset / scrollablePx) * 100f).coerceIn(0f, 100f)
}

internal fun shouldRunPercentRestoreFallback(
    isPreciseRestore: Boolean,
    targetFraction: Float?
): Boolean = !isPreciseRestore || targetFraction == null || targetFraction == 0f

private fun buildScrollSnapshot(
    listState: LazyListState,
    content: ChapterContent,
    stableKeys: List<String>
): ReaderScrollSnapshot? {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstItem = visibleItems.firstOrNull()
    if (content.paragraphs.isEmpty() || firstItem == null || firstItem.size <= 0) {
        return null
    }

    val itemSize = firstItem.size.coerceAtLeast(1)
    val offsetFraction = (listState.firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
    val elementKey = stableKeys.getOrNull(firstItem.index) ?: ""

    // Pixel-weighted progress: stable across image-decode reflow that would otherwise
    // drag percent down as items grow. Unmeasured items off-screen are estimated via the
    // average of currently-measured items; in image-heavy chapters (manhwa) sizes cluster,
    // so the estimate is accurate enough to keep the seek bar from sliding backward.
    val totalItems = layoutInfo.totalItemsCount.coerceAtLeast(1)
    val viewportPx = layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
    val avgItemSizePx = if (visibleItems.isEmpty()) 1f else {
        var sum = 0f
        for (i in 0 until visibleItems.size) {
            sum += visibleItems[i].size
        }
        (sum / visibleItems.size).coerceAtLeast(1f)
    }
    val totalContentPx = totalItems.toFloat() * avgItemSizePx
    val pixelsBeforeFirst = firstItem.index.toFloat() * avgItemSizePx
    val currentPixelOffset = pixelsBeforeFirst + listState.firstVisibleItemScrollOffset.toFloat()

    return ReaderScrollSnapshot(
        scrollOffset = currentPixelOffset,
        maxScrollOffset = totalContentPx,
        viewportHeightInItems = viewportPx,
        index = listState.firstVisibleItemIndex,
        offsetFraction = offsetFraction,
        elementKey = elementKey,
        canScrollForward = listState.canScrollForward,
        firstVisibleItemSize = itemSize
    )
}

internal data class ReaderScrollSnapshot(
    val scrollOffset: Float,
    val maxScrollOffset: Float,
    val viewportHeightInItems: Float,
    val index: Int,
    val offsetFraction: Float,
    val elementKey: String,
    val canScrollForward: Boolean,
    val firstVisibleItemSize: Int
)

@Composable
private fun EdgeNavigationHint(atTop: Boolean, atBottom: Boolean) {
    if (atTop) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            EdgeHintChip(text = "Pull down for previous chapter", icon = Icons.Default.ArrowDownward)
        }
    }
    if (atBottom) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            EdgeHintChip(text = "Pull up for next chapter", icon = Icons.Default.ArrowUpward)
        }
    }
}

@Composable
private fun EdgeHintChip(
    text: String,
    icon: ImageVector
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReaderBottomNavigationBar(
    readerViewModel: ReaderViewModel,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onProgressChange: (Float) -> Unit
) {
    val progressState by readerViewModel.progressState.collectAsState()

    BottomNavigationBar(
        progress = progressState.scrollPosition,
        canNavigatePrevious = canNavigatePrevious,
        canNavigateNext = canNavigateNext,
        onPreviousClick = onPreviousClick,
        onNextClick = onNextClick,
        onProgressChange = onProgressChange
    )
}
