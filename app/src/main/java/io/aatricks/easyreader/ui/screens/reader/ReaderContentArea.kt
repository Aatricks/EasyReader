package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import io.aatricks.easyreader.ui.util.toFontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.components.BottomNavigationBar
import io.aatricks.easyreader.ui.components.ReaderImageView
import io.aatricks.easyreader.ui.components.TopInfoBar
import io.aatricks.easyreader.ui.util.resolveRestoreOffset
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs

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

    val listState = key(content.url) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = uiState.scrollIndex,
            initialFirstVisibleItemScrollOffset = uiState.scrollOffset
        )
    }

    val pagerState = key(content.url) {
        rememberPagerState(
            initialPage = uiState.scrollIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0)),
            initialPageOffsetFraction = 0f
        ) {
            content.paragraphs.size
        }
    }

    val requestedIndices = remember(content.url) { mutableSetOf<Int>() }
    var lastAppliedRestoreOffset by remember(content.url) { mutableStateOf<Int?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(listState.firstVisibleItemIndex, pagerState.currentPage, content.url) {
        val currentIndex = if (uiState.isPagedMode) pagerState.currentPage else listState.firstVisibleItemIndex
        prefetchImages(currentIndex, content, requestedIndices) { url ->
            readerViewModel.prefetchVisibleImage(url, content.url)
        }
    }

    LaunchedEffect(content.url, uiState.isPagedMode, uiState.pendingRestoreOffsetFraction, uiState.scrollIndex) {
        if (uiState.isPagedMode || uiState.pendingRestoreOffsetFraction == null || content.paragraphs.isEmpty()) {
            return@LaunchedEffect
        }

        snapshotFlow {
            val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == uiState.scrollIndex }
            visibleItem?.size ?: 0
        }
            .collect { itemSize ->
                if (itemSize <= 0) return@collect

                val targetIndex = uiState.scrollIndex.coerceIn(0, content.paragraphs.lastIndex)
                val targetOffset = resolveRestoreOffset(
                    savedOffsetPx = uiState.scrollOffset,
                    savedOffsetFraction = uiState.pendingRestoreOffsetFraction,
                    itemSizePx = itemSize
                )
                val currentOffset = listState.firstVisibleItemScrollOffset
                val isAlreadyApplied = listState.firstVisibleItemIndex == targetIndex &&
                    abs(currentOffset - targetOffset) <= 2 &&
                    lastAppliedRestoreOffset == targetOffset

                if (!isAlreadyApplied) {
                    listState.scrollToItem(targetIndex, targetOffset)
                    lastAppliedRestoreOffset = targetOffset
                }
            }
    }

    LaunchedEffect(uiState.seekTrigger) {
        if (content.paragraphs.isNotEmpty() && uiState.seekTrigger > 0L) {
            val targetIndex = uiState.scrollIndex
            val targetOffset = uiState.scrollOffset

            if (uiState.isPagedMode) {
                val page = targetIndex.coerceIn(0, content.paragraphs.size - 1)
                pagerState.scrollToPage(page)
            } else if (targetIndex >= 0) {
                runCatching {
                    listState.scrollToItem(targetIndex, targetOffset)
                }.onFailure {
                    val totalItems = content.paragraphs.size
                    val percent = uiState.scrollPosition.coerceIn(0f, 100f) / 100f
                    val index = (percent * totalItems).toInt().coerceIn(0, totalItems - 1)
                    listState.scrollToItem(index, 0)
                }
            }
        }
    }

    if (uiState.isPagedMode) {
        LaunchedEffect(pagerState.currentPage) {
            val totalItems = content.paragraphs.size
            val currentItem = pagerState.currentPage

            readerViewModel.updateScrollPosition(
                scrollOffset = currentItem.toFloat(),
                maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat(),
                viewportHeight = 0f,
                index = currentItem,
                offset = 0
            )
        }
    } else {
        DisposableEffect(lifecycleOwner, listState, content.url, uiState.isPagedMode) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_PAUSE && event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                if (uiState.isPagedMode) return@LifecycleEventObserver

                val layoutInfo = listState.layoutInfo
                val firstItem = layoutInfo.visibleItemsInfo.firstOrNull()
                val snapshot = firstItem?.let {
                    calculateReaderScrollSnapshot(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemMeasuredIndex = it.index,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        canScrollForward = listState.canScrollForward,
                        totalItemsCount = layoutInfo.totalItemsCount,
                        viewportHeightPx = layoutInfo.viewportSize.height,
                        firstVisibleItemSize = it.size
                    )
                }

                flushReaderLifecycleProgress(
                    snapshot = snapshot,
                    updateScrollPosition = { flushSnapshot ->
                        readerViewModel.updateScrollPosition(
                            scrollOffset = flushSnapshot.scrollOffset,
                            maxScrollOffset = flushSnapshot.maxScrollOffset,
                            viewportHeight = flushSnapshot.viewportHeightInItems,
                            index = flushSnapshot.index,
                            offset = flushSnapshot.offset,
                            canScrollForward = flushSnapshot.canScrollForward,
                            firstVisibleItemSize = flushSnapshot.firstVisibleItemSize
                        )
                    },
                    persistProgress = {
                        coroutineScope.launch {
                            readerViewModel.persistLifecycleProgress()
                        }
                    }
                )
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
                    if (content.paragraphs.isEmpty()) return@collect

                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return@collect

                    val firstItem = visibleItems.first()
                    val snapshot = calculateReaderScrollSnapshot(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemMeasuredIndex = firstItem.index,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        canScrollForward = listState.canScrollForward,
                        totalItemsCount = layoutInfo.totalItemsCount,
                        viewportHeightPx = layoutInfo.viewportSize.height,
                        firstVisibleItemSize = firstItem.size
                    ) ?: return@collect

                    readerViewModel.updateScrollPosition(
                        scrollOffset = snapshot.scrollOffset,
                        maxScrollOffset = snapshot.maxScrollOffset,
                        viewportHeight = snapshot.viewportHeightInItems,
                        index = snapshot.index,
                        offset = snapshot.offset,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector
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

internal data class ReaderScrollSnapshot(
    val scrollOffset: Float,
    val maxScrollOffset: Float,
    val viewportHeightInItems: Float,
    val index: Int,
    val offset: Int,
    val canScrollForward: Boolean,
    val firstVisibleItemSize: Int
)

internal fun calculateReaderScrollSnapshot(
    firstVisibleItemIndex: Int,
    firstVisibleItemMeasuredIndex: Int,
    firstVisibleItemScrollOffset: Int,
    canScrollForward: Boolean,
    totalItemsCount: Int,
    viewportHeightPx: Int,
    firstVisibleItemSize: Int
): ReaderScrollSnapshot? {
    if (totalItemsCount <= 0 || firstVisibleItemSize <= 0) return null

    val itemSize = firstVisibleItemSize.coerceAtLeast(1)
    val currentScrollOffset = firstVisibleItemMeasuredIndex.toFloat() +
        (firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat())
    val viewportHeightInItems = viewportHeightPx.toFloat() / itemSize.toFloat()
    val maxScrollOffset = (totalItemsCount - 1).coerceAtLeast(0).toFloat() + viewportHeightInItems

    return ReaderScrollSnapshot(
        scrollOffset = currentScrollOffset,
        maxScrollOffset = maxScrollOffset,
        viewportHeightInItems = viewportHeightInItems,
        index = firstVisibleItemIndex,
        offset = firstVisibleItemScrollOffset,
        canScrollForward = canScrollForward,
        firstVisibleItemSize = itemSize
    )
}

internal fun flushReaderLifecycleProgress(
    snapshot: ReaderScrollSnapshot?,
    updateScrollPosition: (ReaderScrollSnapshot) -> Unit,
    persistProgress: () -> Unit
) {
    if (snapshot != null) {
        updateScrollPosition(snapshot)
    }
    persistProgress()
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
