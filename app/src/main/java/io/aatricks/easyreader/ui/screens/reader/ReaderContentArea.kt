package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.aatricks.easyreader.ui.components.ReaderBottomEdgeBlur
import io.aatricks.easyreader.ui.components.ReaderTopEdgeBlur
import io.aatricks.easyreader.ui.components.applyReaderEdgeBlur
import io.aatricks.easyreader.ui.components.supportsReaderEdgeBlur
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import io.aatricks.easyreader.ui.screens.reader.ReaderTapAction
import io.aatricks.easyreader.ui.screens.reader.ReaderRenderItem
import io.aatricks.easyreader.ui.screens.reader.ScrollingReaderState
import io.aatricks.easyreader.ui.screens.reader.buildReaderRenderItems
import io.aatricks.easyreader.ui.screens.reader.findRenderIndexForSource
import io.aatricks.easyreader.ui.screens.reader.findSourcePositionForRender
import io.aatricks.easyreader.ui.screens.reader.resolveReaderTapAction
import io.aatricks.easyreader.ui.screens.reader.scrollingReaderView
import io.aatricks.easyreader.ui.screens.reader.shouldRunPercentRestoreFallback

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

// The native drawer opens whenever a drag's horizontal component crosses touch-slop before the
// vertical one, which lets fairly diagonal flicks open it. This raises that bar: the drawer is
// only allowed to claim a drag whose horizontal travel leads vertical by at least this factor
// (~30deg from horizontal). Diagonal flicks between here and 45deg are swallowed so they neither
// open the drawer nor scroll; steeper drags fall through to the native follow-the-finger gesture.
private const val DRAWER_OPEN_ANGLE_RATIO = 1.75f
private const val READER_TEXT_MEASURER_CACHE_SIZE = 64
private const val READER_LINE_BOUNDARY_CACHE_SIZE = 2_048

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
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer(cacheSize = READER_TEXT_MEASURER_CACHE_SIZE)
    val textLineCache = remember(content.url) { ReaderTextLineCache(READER_LINE_BOUNDARY_CACHE_SIZE) }
    var viewportSize by remember(content.url) { mutableStateOf(IntSize.Zero) }
    val pagedTextStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = uiState.fontSize.sp,
        lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
        fontFamily = fontFamily
    )

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

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val renderItems = remember(content, isManhwa, screenWidthPx) {
        buildReaderRenderItems(content, isManhwa, screenWidthPx)
    }

    val paginationElements = remember(content.paragraphs, uiState.isPagedMode) {
        paginationElementsForMode(uiState.isPagedMode, content.paragraphs)
    }
    var generatedReaderPages by remember(content.url) {
        mutableStateOf<List<ReaderPage>>(emptyList())
    }

    LaunchedEffect(
        paginationElements,
        viewportSize,
        uiState.isPagedMode,
        pagedTextStyle,
        uiState.margins,
        uiState.verticalMargins,
        uiState.paragraphSpacing,
        density,
        textMeasurer,
        textLineCache
    ) {
        if (!uiState.isPagedMode) {
            generatedReaderPages = emptyList()
            return@LaunchedEffect
        }
        val horizontalPaddingPx = with(density) { (uiState.margins.dp * 2).roundToPx() }
        val verticalPaddingPx = with(density) { (uiState.verticalMargins.dp * 2).toPx() }
        val availableWidthPx = (viewportSize.width - horizontalPaddingPx).coerceAtLeast(0)
        val availableHeightPx = (viewportSize.height - verticalPaddingPx).coerceAtLeast(0f)
        val lineHeightPx = with(density) { pagedTextStyle.lineHeight.toPx() }
        val paragraphSpacingPx = with(density) {
            (uiState.fontSize * uiState.paragraphSpacing).dp.toPx()
        }

        val replacement = paginateReaderContentForMode(
            isPagedMode = true,
            request = ReaderPaginationRequest(
                elements = paginationElements,
                pageHeightPx = availableHeightPx,
                lineHeightPx = lineHeightPx,
                paragraphSpacingPx = paragraphSpacingPx,
                lineEndsFor = { text ->
                val key = TextMeasureKey(
                    content = text,
                    availableWidthPx = availableWidthPx,
                    fontSizeSp = uiState.fontSize,
                    lineHeightPx = lineHeightPx,
                    fontFamily = uiState.fontFamily
                )
                textLineCache.getOrMeasure(key) {
                    if (availableWidthPx <= 0) {
                        listOf(text.length)
                    } else {
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = pagedTextStyle,
                            constraints = Constraints(maxWidth = availableWidthPx)
                        )
                        List(layout.lineCount) { line -> layout.getLineEnd(line) }
                    }
                }
                }
            )
        )
        generatedReaderPages = replacement
    }

    val fallbackReaderPages = remember(paginationElements, uiState.isPagedMode) {
        fallbackReaderPagesForMode(uiState.isPagedMode, paginationElements)
    }
    val readerPages = if (uiState.isPagedMode && generatedReaderPages.isEmpty()) {
        fallbackReaderPages
    } else {
        generatedReaderPages
    }

    // No init values — single restore path through LaunchedEffect below. Initial values would
    // race with the LaunchedEffect and create the "two paths, one of them silently wrong" bug.
    val listState = key(content.url) { rememberLazyListState() }
    var positionedRenderItems by remember(content.url) { mutableStateOf(renderItems) }

    LaunchedEffect(renderItems) {
        if (renderItems == positionedRenderItems) return@LaunchedEffect
        val previousIndex = listState.firstVisibleItemIndex
        val previousSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == previousIndex }
            ?.size
            ?.coerceAtLeast(1) ?: 1
        val previousFraction = listState.firstVisibleItemScrollOffset.toFloat() / previousSize
        val sourcePosition = findSourcePositionForRender(
            positionedRenderItems,
            previousIndex,
            previousFraction
        )
        positionedRenderItems = renderItems
        kotlinx.coroutines.yield()
        val target = findRenderIndexForSource(renderItems, sourcePosition.first, sourcePosition.second)
        val targetSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == target.first }
            ?.size
            ?.coerceAtLeast(1) ?: 1
        listState.scrollToItem(target.first, (targetSize * target.second).toInt())
    }

    val pagerState = key(content.url) {
        rememberPagerState(
            initialPage = readerPageIndexForPosition(
                pages = readerPages,
                sourceIndex = uiState.scrollIndex,
                sourceOffsetFraction = uiState.restoreOffsetFraction.coerceAtLeast(0f)
            ),
            initialPageOffsetFraction = 0f
        ) {
            readerPages.size
        }
    }
    var positionedReaderPages by remember(content.url) { mutableStateOf(readerPages) }

    LaunchedEffect(readerPages) {
        val previousPosition = positionedReaderPages.getOrNull(pagerState.currentPage)?.position
        if (previousPosition != null && readerPages.isNotEmpty()) {
            pagerState.scrollToPage(
                readerPageIndexForPosition(
                    pages = readerPages,
                    sourceIndex = previousPosition.sourceIndex,
                    sourceOffsetFraction = previousPosition.sourceOffsetFraction
                )
            )
        }
        positionedReaderPages = readerPages
    }

    val requestedIndices = remember(content.url) { mutableSetOf<Int>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val hapticFeedback = LocalHapticFeedback.current

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
    LaunchedEffect(content.url, uiState.isPagedMode, positionedReaderPages, positionedRenderItems) {
        snapshotFlow {
            if (uiState.isPagedMode) {
                positionedReaderPages.getOrNull(pagerState.currentPage)?.position?.sourceIndex ?: 0
            } else {
                positionedRenderItems.getOrNull(listState.firstVisibleItemIndex)?.sourceElementIndex ?: 0
            }
        }.collect { currentIndex ->
            prefetchImages(currentIndex, content, requestedIndices) { url ->
                readerViewModel.prefetchVisibleImage(url, content.url)
            }
        }
    }

    LaunchedEffect(content.url, uiState.seekTrigger) {
        runScrollRestore(
            ScrollRestoreRequest(
                content = content,
                listState = listState,
                pagedLayout = PagedRestoreLayout(positionedReaderPages, pagerState),
                readerViewModel = readerViewModel,
                stableKeys = stableKeys,
                renderItems = positionedRenderItems
            )
        )
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
        LaunchedEffect(pagerState.currentPage, readerPages) {
            if (positionedReaderPages !== readerPages) return@LaunchedEffect
            val totalItems = content.paragraphs.size
            val currentPage = pagerState.currentPage
            val position = readerPages.getOrNull(currentPage)?.position ?: ReaderPagePosition(0, 0f)
            val currentKey = stableKeys.getOrNull(position.sourceIndex) ?: ""

            readerViewModel.updateScrollPosition(
                scrollOffset = position.sourceIndex + position.sourceOffsetFraction,
                maxScrollOffset = totalItems.toFloat(),
                viewportHeight = 0f,
                index = position.sourceIndex,
                offsetFraction = position.sourceOffsetFraction,
                elementKey = currentKey,
                canScrollForward = currentPage < readerPages.lastIndex,
                firstVisibleItemSize = PAGED_POSITION_ITEM_SIZE_PX
            )
        }
    } else {
        DisposableEffect(lifecycleOwner, listState, content.url, uiState.isPagedMode, positionedRenderItems) {
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

                val snapshot = buildScrollSnapshot(
                    listState,
                    content,
                    stableKeys,
                    positionedRenderItems
                ) ?: return@LifecycleEventObserver
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

        LaunchedEffect(listState, content.url, positionedRenderItems) {
            snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.canScrollForward
                )
            }
                .conflate()
                .collect {
                    val snapshot = buildScrollSnapshot(
                        listState,
                        content,
                        stableKeys,
                        positionedRenderItems
                    ) ?: return@collect
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
            .onSizeChanged { viewportSize = it }
            .nestedScroll(nestedScrollConnection)
            // Angle gate for the native drawer's open-swipe. Runs on the Main pass ahead of the
            // drawer's own draggable (an ancestor), so consuming a change here cancels the drawer's
            // slop detection (DragGestureDetector bails on a consumed change) -- but only for drags
            // that aren't horizontal enough. Steep-horizontal drags are left untouched so the
            // native follow-the-finger open still works; vertical drags are left for the list.
            .pointerInput(uiState.isPagedMode) {
                if (uiState.isPagedMode) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var blocking = false
                    var tracking = true
                    while (tracking) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val adx = change?.let { kotlin.math.abs(it.position.x - down.position.x) } ?: 0f
                        val ady = change?.let { kotlin.math.abs(it.position.y - down.position.y) } ?: 0f
                        when {
                            change == null || !change.pressed -> tracking = false
                            blocking -> change.consume()
                            // Something deeper (the list) already took it -- leave it alone.
                            change.isConsumed -> tracking = false
                            adx < slop && ady < slop -> Unit // below slop: undecided, keep waiting
                            // Steep-horizontal: hand off to the native drawer gesture.
                            adx >= ady * DRAWER_OPEN_ANGLE_RATIO -> tracking = false
                            // Vertical-dominant: it's a scroll, let the list have it.
                            ady > adx -> tracking = false
                            // Diagonal-but-not-steep: swallow so the drawer can't open on it.
                            else -> {
                                blocking = true
                                change.consume()
                            }
                        }
                    }
                }
            }
            .background(bgColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Single full-screen tap authority for toggling the reader controls. The
                // per-item clickables only cover text glyphs, so horizontal margins,
                // inter-paragraph gaps, and empty space below a short chapter were dead
                // zones where a tap did nothing — felt like the tap "didn't register",
                // worst when dismissing the menu (its bars shrink the reliable target).
                // detectTapGestures cancels on any consumed movement, so the LazyColumn /
                // pager still win drags; only clean taps that no child consumed reach here.
                .pointerInput(
                    uiState.isPagedMode,
                    uiState.isRtl,
                    uiState.canNavigateNext,
                    uiState.canNavigatePrevious,
                    pagerState.pageCount
                ) {
                    detectTapGestures { offset ->
                        val xFraction = offset.x / size.width.toFloat()
                        val action = resolveReaderTapAction(
                            xFraction = xFraction,
                            isPaged = uiState.isPagedMode,
                            isRtl = uiState.isRtl
                        )
                        when (action) {
                            ReaderTapAction.TOGGLE_CONTROLS -> readerViewModel.toggleControls()
                            ReaderTapAction.PAGE_FORWARD -> {
                                val nextPage = pagerState.currentPage + 1
                                if (nextPage < pagerState.pageCount) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(nextPage)
                                    }
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                } else if (uiState.canNavigateNext) {
                                    readerViewModel.navigateToNextChapter()
                                }
                            }
                            ReaderTapAction.PAGE_BACK -> {
                                val prevPage = pagerState.currentPage - 1
                                if (prevPage >= 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(prevPage)
                                    }
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                } else if (uiState.canNavigatePrevious) {
                                    readerViewModel.navigateToPreviousChapter(fromBottom = true)
                                }
                            }
                        }
                    }
                }
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
                CompositionLocalProvider(localReaderPages provides readerPages) {
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
                }
            } else {
                scrollingReaderView(
                    ScrollingReaderState(
                        content = content,
                        renderItems = positionedRenderItems,
                        listState = listState,
                        uiState = uiState,
                        isManhwa = isManhwa,
                        fontFamily = fontFamily,
                        backgroundColor = bgColor,
                        textColor = textColor,
                        readerViewModel = readerViewModel
                    )
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                TopInfoBar(
                    novelName = uiState.novelName,
                    chapterTitle = uiState.chapterTitle,
                    onLibraryClick = onLibraryClick,
                    onShowChapterList = onShowChapterList,
                    onShowSettings = onShowSettings
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f)
                            )
                        )
                    )
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

private data class PagedRestoreLayout(
    val pages: List<ReaderPage>,
    val pagerState: androidx.compose.foundation.pager.PagerState
)

private data class ScrollRestoreRequest(
    val content: ChapterContent,
    val listState: LazyListState,
    val pagedLayout: PagedRestoreLayout,
    val readerViewModel: ReaderViewModel,
    val stableKeys: List<String>,
    val renderItems: List<ReaderRenderItem>
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private suspend fun runScrollRestore(request: ScrollRestoreRequest): Unit = with(request) {
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

    // isPagedMode is a UI-mode flag, not a stale-able progress field — always read it live.
    val isPagedMode = readerViewModel.uiState.value.isPagedMode
    // Anchor source: a genuine load/seek replays the frozen uiState anchor (unchanged
    // behavior); a bare recomposition (e.g. returning from the full library screen) re-applies
    // the live progressState position, so the user is never yanked back to a stale open-time
    // anchor and no stale value is later persisted.
    val anchor = readerViewModel.consumeRestoreAnchor()
    val targetIndex = resolveRestoreIndex(anchor.elementKey, anchor.scrollIndex, stableKeys)
        .coerceIn(0, content.paragraphs.lastIndex)
    val targetFraction = anchor.offsetFraction
        .takeIf { it >= 0f }
        ?.coerceIn(0f, 1f)
    val targetRenderPosition = findRenderIndexForSource(
        renderItems,
        targetIndex,
        targetFraction ?: 0f
    )

    // One-shot jumps: paged mode uses the page index as the whole position; from-bottom
    // navigation seeks the final item end. Anything else lands at the item and then chases
    // the intra-item fraction via the watch-until-stable loop below.
    val handledAsOneShot = when {
        isPagedMode -> {
            val pageIndex = readerPageIndexForPosition(
                pages = pagedLayout.pages,
                sourceIndex = targetIndex,
                sourceOffsetFraction = targetFraction ?: 0f
            )
            runCatching { pagedLayout.pagerState.scrollToPage(pageIndex) }
            true
        }
        anchor.targetScrollPosition == 100f -> {
            runCatching { listState.scrollToItem(renderItems.lastIndex, Int.MAX_VALUE) }
            true
        }
        else -> {
            // Land at the item first so the LazyList composes it. Offset comes after measurement.
            runCatching { listState.scrollToItem(targetRenderPosition.first, 0) }
            false
        }
    }

    if (!handledAsOneShot) {
        awaitStableRestore(
            listState,
            targetRenderPosition.first,
            targetRenderPosition.second.takeIf { targetFraction != null },
            readerViewModel
        )

        // Final percent-based smoke check. Gates on userHasDragged (not the looser
        // hasUserInteractedSinceLoad) so programmatic / reflow-induced scroll events don't
        // suppress self-heal. Runs in every imprecise case AND for precise restores with no
        // intra-item fraction — catches "landed at the wrong index but seek bar says 89%".
        runPercentRestoreFallback(request, anchor, targetFraction)
    }

    readerViewModel.markRestoreDone()
}

private suspend fun runPercentRestoreFallback(
    request: ScrollRestoreRequest,
    anchor: ReaderViewModel.RestoreAnchor,
    targetFraction: Float?
) = with(request) {
    if (readerViewModel.userHasDragged ||
        !shouldRunPercentRestoreFallback(anchor.isPreciseRestore, targetFraction)
    ) {
        return@with
    }
    val visiblePercent = computeVisiblePercent(listState, renderItems.size)
    val targetPercent = anchor.scrollPosition
    if (visiblePercent != null && abs(visiblePercent - targetPercent) > RESTORE_PERCENT_TOLERANCE) {
        val fallbackIndex = ((targetPercent / 100f) * content.paragraphs.lastIndex).toInt()
            .coerceIn(0, content.paragraphs.lastIndex)
        val fallbackRenderIndex = findRenderIndexForSource(renderItems, fallbackIndex, 0f).first
        runCatching { listState.scrollToItem(fallbackRenderIndex, 0) }
    }
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
    elementKey: String,
    fallbackIndex: Int,
    stableKeys: List<String>
): Int {
    if (elementKey.isNotEmpty()) {
        val idx = stableKeys.indexOf(elementKey)
        if (idx >= 0) return idx
    }
    return fallbackIndex
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

private fun buildScrollSnapshot(
    listState: LazyListState,
    content: ChapterContent,
    stableKeys: List<String>,
    renderItems: List<ReaderRenderItem>
): ReaderScrollSnapshot? {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstItem = visibleItems.firstOrNull()
    if (content.paragraphs.isEmpty() || firstItem == null || firstItem.size <= 0) {
        return null
    }

    val itemSize = firstItem.size.coerceAtLeast(1)
    val localOffsetFraction =
        (listState.firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
    val sourcePosition = findSourcePositionForRender(
        renderItems,
        firstItem.index,
        localOffsetFraction
    )
    val elementKey = stableKeys.getOrNull(sourcePosition.first) ?: ""

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
        index = sourcePosition.first,
        offsetFraction = sourcePosition.second,
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
        modifier = Modifier.padding(vertical = EasyReaderSpacing.sm, horizontal = EasyReaderSpacing.md),
        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = EasyReaderSpacing.sm, vertical = 6.dp),
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
