package io.aatricks.novelscraper.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aatricks.novelscraper.data.model.ChapterContent
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.components.BottomNavigationBar
import io.aatricks.novelscraper.ui.components.ReaderImageView
import io.aatricks.novelscraper.ui.components.TopInfoBar
import io.aatricks.novelscraper.ui.util.resolveRestoreOffset
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlin.math.abs

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
internal fun ContentArea(
    uiState: ReaderViewModel.ReaderUiState,
    content: ChapterContent,
    readerViewModel: ReaderViewModel,
    onLibraryClick: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
): Unit {
    val fontFamily = when (uiState.fontFamily) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

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
        LaunchedEffect(listState, content.url) {
            snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.canScrollForward
                )
            }
                .conflate()
                .sample(120)
                .collect {
                    if (content.paragraphs.isEmpty()) return@collect

                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return@collect

                    val firstItem = visibleItems.first()
                    val totalItems = layoutInfo.totalItemsCount

                    val currentScrollOffset = firstItem.index.toFloat() +
                        (listState.firstVisibleItemScrollOffset.toFloat() / firstItem.size.coerceAtLeast(1).toFloat())

                    val viewportHeightInItems =
                        layoutInfo.viewportSize.height.toFloat() / firstItem.size.coerceAtLeast(1).toFloat()
                    val maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat()

                    readerViewModel.updateScrollPosition(
                        scrollOffset = currentScrollOffset,
                        maxScrollOffset = maxScrollOffset + viewportHeightInItems,
                        viewportHeight = viewportHeightInItems,
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                        canScrollForward = listState.canScrollForward,
                        firstVisibleItemSize = firstItem.size
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
