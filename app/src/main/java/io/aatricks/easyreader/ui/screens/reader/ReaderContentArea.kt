package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import io.aatricks.easyreader.ui.util.toFontFamily
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.MIN_STABLE_ITEM_SIZE_PX
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.stableContentElementKey
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val RESTORE_SMOKE_CHECK_DELAY_MS = 500L
private const val RESTORE_PERCENT_TOLERANCE = 5f

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

    LaunchedEffect(listState.firstVisibleItemIndex, pagerState.currentPage, content.url) {
        val currentIndex = if (uiState.isPagedMode) pagerState.currentPage else listState.firstVisibleItemIndex
        prefetchImages(currentIndex, content, requestedIndices) { url ->
            readerViewModel.prefetchVisibleImage(url, content.url)
        }
    }

    // ─── Unified restore path ───────────────────────────────────────────────
    // Single LaunchedEffect handles BOTH initial load and seek-bar drags. Resolution order:
    //   1. element-key anchor (survives chapter reparse) → 2. saved index → 3. percent fallback
    // After landing, runs a smoke check: if visible % drifts > RESTORE_PERCENT_TOLERANCE from the
    // saved %, falls back to percent-based scroll (defends against async-image-resize drift).
    LaunchedEffect(content.url, uiState.seekTrigger) {
        if (content.paragraphs.isEmpty()) return@LaunchedEffect

        val targetIndex = resolveRestoreIndex(content, uiState)
            .coerceIn(0, content.paragraphs.lastIndex)
        val targetFraction = uiState.restoreOffsetFraction
            .takeIf { it >= 0f }
            ?.coerceIn(0f, 1f)

        // Paged mode: page index is the whole position, no intra-page fraction to chase.
        if (uiState.isPagedMode) {
            runCatching { pagerState.scrollToPage(targetIndex) }
            return@LaunchedEffect
        }

        // From-bottom navigation always seeks the final item end.
        if (uiState.targetScrollPosition == 100f) {
            runCatching { listState.scrollToItem(content.paragraphs.lastIndex, Int.MAX_VALUE) }
            return@LaunchedEffect
        }

        // Land at the item first so the LazyList composes it. Offset comes after measurement.
        runCatching { listState.scrollToItem(targetIndex, 0) }

        if (targetFraction == null || targetFraction == 0f) {
            return@LaunchedEffect
        }

        // Wait for the target item to reach a meaningful size — async image loads pass through
        // a placeholder height first, and applying the fraction against the placeholder
        // produces a meaningless offset.
        val itemSize = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
                ?.size ?: 0
        }.first { it >= MIN_STABLE_ITEM_SIZE_PX }

        val targetOffsetPx = (itemSize * targetFraction).toInt().coerceIn(0, itemSize)
        runCatching { listState.scrollToItem(targetIndex, targetOffsetPx) }

        // Self-heal smoke check — verify after layout has settled. Skip if the user has already
        // taken the wheel: yanking them away from their own scroll is worse than tolerating a
        // small restore mismatch.
        kotlinx.coroutines.delay(RESTORE_SMOKE_CHECK_DELAY_MS)
        if (readerViewModel.hasUserInteractedSinceLoad) return@LaunchedEffect
        val visiblePercent = computeVisiblePercent(listState, content.paragraphs.size)
        val targetPercent = uiState.scrollPosition
        if (visiblePercent != null && abs(visiblePercent - targetPercent) > RESTORE_PERCENT_TOLERANCE) {
            val fallbackIndex = ((targetPercent / 100f) * content.paragraphs.lastIndex).toInt()
                .coerceIn(0, content.paragraphs.lastIndex)
            runCatching { listState.scrollToItem(fallbackIndex, 0) }
        }
    }

    if (uiState.isPagedMode) {
        LaunchedEffect(pagerState.currentPage) {
            val totalItems = content.paragraphs.size
            val currentItem = pagerState.currentPage
            val currentKey = content.paragraphs.getOrNull(currentItem)
                ?.let { stableContentElementKey(content.url, currentItem, it) }
                ?: ""

            readerViewModel.updateScrollPosition(
                scrollOffset = currentItem.toFloat(),
                maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat(),
                viewportHeight = 0f,
                index = currentItem,
                offsetFraction = 0f,
                elementKey = currentKey,
                firstVisibleItemSize = Int.MAX_VALUE
            )
        }
    } else {
        DisposableEffect(lifecycleOwner, listState, content.url, uiState.isPagedMode) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_PAUSE && event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                if (uiState.isPagedMode) return@LifecycleEventObserver

                val snapshot = buildScrollSnapshot(listState, content) ?: return@LifecycleEventObserver
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
                    val snapshot = buildScrollSnapshot(listState, content) ?: return@collect
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

    val density = androidx.compose.ui.platform.LocalDensity.current
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

private fun resolveRestoreIndex(
    content: ChapterContent,
    uiState: ReaderViewModel.ReaderUiState
): Int {
    val key = uiState.restoreElementKey
    if (key.isNotEmpty()) {
        content.paragraphs.forEachIndexed { idx, element ->
            if (stableContentElementKey(content.url, idx, element) == key) return idx
        }
    }
    return uiState.scrollIndex
}

private fun computeVisiblePercent(listState: LazyListState, totalItems: Int): Float? {
    if (totalItems <= 0) return null
    val firstItem = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
    if (firstItem.size <= 0) return null
    val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
    val viewportInItems = viewportHeight / firstItem.size
    val currentPos = firstItem.index.toFloat() + (listState.firstVisibleItemScrollOffset.toFloat() / firstItem.size)
    val maxPos = (totalItems - 1).coerceAtLeast(0).toFloat() + viewportInItems
    val denom = (maxPos - viewportInItems).coerceAtLeast(0.0001f)
    return ((currentPos / denom) * 100f).coerceIn(0f, 100f)
}

private fun buildScrollSnapshot(listState: LazyListState, content: ChapterContent): ReaderScrollSnapshot? {
    if (content.paragraphs.isEmpty()) return null
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstItem = visibleItems.firstOrNull() ?: return null
    if (firstItem.size <= 0) return null

    val itemSize = firstItem.size.coerceAtLeast(1)
    val currentScrollOffset = firstItem.index.toFloat() +
        (listState.firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat())
    val viewportHeightInItems = layoutInfo.viewportSize.height.toFloat() / itemSize.toFloat()
    val maxScrollOffset = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0).toFloat() + viewportHeightInItems
    val offsetFraction = (listState.firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
    val elementKey = content.paragraphs.getOrNull(firstItem.index)
        ?.let { stableContentElementKey(content.url, firstItem.index, it) }
        ?: ""

    return ReaderScrollSnapshot(
        scrollOffset = currentScrollOffset,
        maxScrollOffset = maxScrollOffset,
        viewportHeightInItems = viewportHeightInItems,
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
