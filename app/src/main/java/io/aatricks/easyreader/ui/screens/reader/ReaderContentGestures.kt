package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlin.math.abs

// Slop for "user actually started scrolling/pulling" detection. 5px was too aggressive and
// produced false-positive chapter pulls during deliberate scrolls; ~10px matches platform
// ViewConfiguration touchSlop without going so high that intentional pulls feel sluggish.
private const val USER_SCROLL_START_THRESHOLD_PX = 10f

internal fun shouldDispatchReaderScrollStart(
    available: Offset,
    hasHandledCurrentGesture: Boolean
): Boolean {
    if (hasHandledCurrentGesture) return false
    return abs(available.y) > USER_SCROLL_START_THRESHOLD_PX ||
        abs(available.x) > USER_SCROLL_START_THRESHOLD_PX
}

// The pull scrim only darkens the reader background to about 60% of the way to black, so a
// bright green on the LIGHT or SEPIA reader theme lands near 1:1 exactly when the user needs to
// read it. Both the resting and the released state are picked from the reader theme instead.
private val RELEASE_ACCENT_ON_DARK = Color(0xFF4CAF50)
private val RELEASE_ACCENT_ON_LIGHT = Color(0xFF1B5E20)
private const val PULL_SCRIM_MAX_ALPHA = 0.4f
private const val PULL_ARROW_FLIP_DEGREES = 180f
private val PULL_ARROW_SIZE = 48.dp

private fun pullOverlayAlignment(isPagedMode: Boolean, isPrevious: Boolean, isRtl: Boolean): Alignment = when {
    !isPagedMode -> if (isPrevious) Alignment.TopCenter else Alignment.BottomCenter
    isPrevious -> if (isRtl) Alignment.CenterStart else Alignment.CenterEnd
    else -> if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
}

private fun pullOverlayIcon(isPagedMode: Boolean, isPrevious: Boolean, isRtl: Boolean): ImageVector = when {
    !isPagedMode -> if (isPrevious) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
    isPrevious -> if (isRtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward
    else -> if (isRtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack
}

private fun pullOverlayLabel(isPrevious: Boolean, isThresholdReached: Boolean): String = when {
    isPrevious && isThresholdReached -> "Release for previous chapter"
    isPrevious -> "Pull for previous chapter"
    isThresholdReached -> "Release for next chapter"
    else -> "Pull for next chapter"
}

@Composable
internal fun PullToNavigateOverlay(
    pullAmount: Float,
    threshold: Float,
    isThresholdReached: Boolean,
    uiState: ReaderViewModel.ReaderUiState
): Unit {
    if (abs(pullAmount) <= 0f) return

    val isPagedMode = uiState.isPagedMode
    val isRtl = uiState.isRtl
    val readerTheme = uiState.readerTheme
    val isPrevious = if (isPagedMode) {
        if (isRtl) pullAmount < 0 else pullAmount > 0
    } else {
        pullAmount > 0
    }

    val releaseAccent = if (readerTheme.backgroundColor.luminance() < DARK_SURFACE_LUMINANCE) {
        RELEASE_ACCENT_ON_DARK
    } else {
        RELEASE_ACCENT_ON_LIGHT
    }
    val arrowColor by animateColorAsState(
        if (isThresholdReached) releaseAccent else readerTheme.textColor,
        label = "arrowColor"
    )
    val rotation by animateFloatAsState(
        if (isThresholdReached) PULL_ARROW_FLIP_DEGREES else 0f,
        label = "arrowRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = (abs(pullAmount) / threshold * PULL_SCRIM_MAX_ALPHA)
                        .coerceAtMost(PULL_SCRIM_MAX_ALPHA)
                )
            ),
        contentAlignment = pullOverlayAlignment(isPagedMode, isPrevious, isRtl)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(EasyReaderSpacing.xxl)
        ) {
            Icon(
                imageVector = pullOverlayIcon(isPagedMode, isPrevious, isRtl),
                contentDescription = null,
                tint = arrowColor,
                modifier = Modifier
                    .size(PULL_ARROW_SIZE)
                    .rotate(if (isPagedMode) 0f else rotation)
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
            Text(
                text = pullOverlayLabel(isPrevious, isThresholdReached),
                color = arrowColor,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberReaderNestedScrollConnection(
    uiState: ReaderViewModel.ReaderUiState,
    pagerState: PagerState,
    listState: LazyListState,
    content: ChapterContent,
    threshold: Float,
    onHideControls: () -> Unit,
    onUserInteraction: () -> Unit,
    onPullAmountChange: (Float) -> Unit,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit
): NestedScrollConnection {
    val currentUiState by rememberUpdatedState(uiState)
    val currentOnHideControls by rememberUpdatedState(onHideControls)
    val currentOnUserInteraction by rememberUpdatedState(onUserInteraction)
    val currentOnPullAmountChange by rememberUpdatedState(onPullAmountChange)
    val currentOnNavigatePrevious by rememberUpdatedState(onNavigatePrevious)
    val currentOnNavigateNext by rememberUpdatedState(onNavigateNext)

    val hapticFeedback = LocalHapticFeedback.current
    var pullAmount by remember { mutableFloatStateOf(0f) }
    var wasThresholdReached by remember { mutableStateOf(false) }
    var handledUserScrollStart by remember { mutableStateOf(false) }

    return remember(content, pagerState, listState, threshold) {
        object : NestedScrollConnection {
            private fun updatePullAmount(newValue: Float) {
                val isReached = abs(newValue) >= threshold
                if (isReached != wasThresholdReached) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    wasThresholdReached = isReached
                }
                pullAmount = newValue
                currentOnPullAmountChange(newValue)
            }

            private fun resetPullAmount() {
                wasThresholdReached = false
                pullAmount = 0f
                currentOnPullAmountChange(0f)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val state = currentUiState
                if (source == NestedScrollSource.UserInput &&
                    shouldDispatchReaderScrollStart(available, handledUserScrollStart)
                ) {
                    if (state.showControls) {
                        currentOnHideControls()
                    }
                    currentOnUserInteraction()
                    handledUserScrollStart = true
                }

                if (state.isPagedMode) {
                    if (pullAmount > 0 && available.x < 0) {
                        val consumed = available.x.coerceAtLeast(-pullAmount)
                        updatePullAmount(pullAmount + consumed)
                        return Offset(consumed, 0f)
                    }
                    if (pullAmount < 0 && available.x > 0) {
                        val consumed = available.x.coerceAtMost(-pullAmount)
                        updatePullAmount(pullAmount + consumed)
                        return Offset(consumed, 0f)
                    }
                } else {
                    if (pullAmount > 0 && available.y < 0) {
                        val consumed = available.y.coerceAtLeast(-pullAmount)
                        updatePullAmount(pullAmount + consumed)
                        return Offset(0f, consumed)
                    }
                    if (pullAmount < 0 && available.y > 0) {
                        val consumed = available.y.coerceAtMost(-pullAmount)
                        updatePullAmount(pullAmount + consumed)
                        return Offset(0f, consumed)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val state = currentUiState

                if (state.isPagedMode) {
                    val isAtStart = pagerState.currentPage == 0
                    val isAtEnd = pagerState.currentPage == pagerState.pageCount - 1
                    val isAtAnyEdge = isAtStart || isAtEnd

                    if (state.isRtl) {
                        if (available.x < 0 && isAtStart && state.canNavigatePrevious) {
                            updatePullAmount(pullAmount + available.x * 0.5f)
                            return Offset(available.x, 0f)
                        } else if (available.x > 0 && isAtEnd && state.canNavigateNext) {
                            updatePullAmount(pullAmount + available.x * 0.5f)
                            return Offset(available.x, 0f)
                        }
                    } else {
                        if (available.x > 0 && isAtStart && state.canNavigatePrevious) {
                            updatePullAmount(pullAmount + available.x * 0.5f)
                            return Offset(available.x, 0f)
                        } else if (available.x < 0 && isAtEnd && state.canNavigateNext) {
                            updatePullAmount(pullAmount + available.x * 0.5f)
                            return Offset(available.x, 0f)
                        }
                    }

                    if (pullAmount != 0f && !isAtAnyEdge) {
                        resetPullAmount()
                    }
                } else {
                    val isAtTop = !listState.canScrollBackward
                    val isAtBottom = !listState.canScrollForward

                    if (available.y > 0 && isAtTop && state.canNavigatePrevious) {
                        updatePullAmount(pullAmount + available.y * 0.5f)
                        return Offset(0f, available.y)
                    } else if (available.y < 0 && isAtBottom && state.canNavigateNext) {
                        updatePullAmount(pullAmount + available.y * 0.5f)
                        return Offset(0f, available.y)
                    }

                    if (pullAmount != 0f && !isAtTop && !isAtBottom) {
                        resetPullAmount()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val state = currentUiState
                if (abs(pullAmount) >= threshold) {
                    val isPrevious = if (state.isPagedMode) {
                        if (state.isRtl) pullAmount < 0 else pullAmount > 0
                    } else {
                        pullAmount > 0
                    }

                    if (isPrevious) {
                        currentOnNavigatePrevious()
                    } else {
                        currentOnNavigateNext()
                    }
                }
                resetPullAmount()
                handledUserScrollStart = false
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                handledUserScrollStart = false
                return Velocity.Zero
            }
        }
    }
}

// Disk warm-ahead window. Kept small for image-heavy (manhwa) chapters: each item is a large
// decode, so a wide window inflates coroutine fan-out and disk-trim churn that competes with
// on-screen decoding. LazyColumn governs its own compose-ahead, so visible loading is unaffected.
private const val PREFETCH_AHEAD = 4
private const val PREFETCH_BEHIND = 1

internal fun prefetchImages(
    currentIndex: Int,
    content: ChapterContent,
    requestedIndices: MutableSet<Int>,
    onEnqueue: (String) -> Unit
) {
    val prefetchAhead = PREFETCH_AHEAD
    val prefetchBehind = PREFETCH_BEHIND

    val startRange = (currentIndex - prefetchBehind).coerceAtLeast(0)
    val endRange = (currentIndex + prefetchAhead).coerceAtMost(content.paragraphs.size - 1)

    for (i in startRange..endRange) {
        if (i == currentIndex) continue
        if (i in requestedIndices) continue

        content.paragraphs.getOrNull(i)?.let { element ->
            when (element) {
                is ContentElement.Image -> {
                    onEnqueue(element.url)
                    requestedIndices.add(i)
                }

                is ContentElement.ImageGroup -> {
                    element.images.forEach { img -> onEnqueue(img.url) }
                    requestedIndices.add(i)
                }

                else -> requestedIndices.add(i)
            }
        }
    }
}
