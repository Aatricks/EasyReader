package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val ZOOM_STATE_EPSILON = 0.05f

internal fun isZoomed(scale: Float, minScale: Float, epsilon: Float = ZOOM_STATE_EPSILON): Boolean {
    return scale > (minScale + epsilon)
}

internal fun shouldHandleTap(scale: Float, minScale: Float, lockTapWhileZoomed: Boolean): Boolean {
    return !lockTapWhileZoomed || !isZoomed(scale = scale, minScale = minScale)
}

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 3f,
    enableZoom: Boolean = false,
    dynamicHeight: Boolean = false,
    zoomStateKey: Any? = null,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    lockTapWhileZoomed: Boolean = false,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var scale by remember(zoomStateKey, minScale) { mutableFloatStateOf(minScale) }
    var offsetX by remember(zoomStateKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(zoomStateKey) { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoomed by remember(zoomStateKey) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    fun emitZoomState(currentScale: Float) {
        val nowZoomed = isZoomed(scale = currentScale, minScale = minScale)
        if (nowZoomed != zoomed) {
            zoomed = nowZoomed
            currentOnZoomChanged?.invoke(nowZoomed)
        }
    }

    LaunchedEffect(zoomStateKey, minScale) {
        emitZoomState(minScale)
    }

    fun clampOffset(value: Float, contentSize: Float, currentScale: Float, isDynamic: Boolean = false): Float {
        val scaledContentSize = contentSize * currentScale
        // In dynamic mode, the container grows with the content, but the viewable area is still constrained by the screen/parent
        // However, the logic here is used for both X and Y. For Y in dynamic mode, we want to allow panning
        // if the scaled height is larger than the original unscaled height (container).
        val effectiveContainerSize = contentSize

        if (scaledContentSize <= effectiveContainerSize) return 0f
        val maxOffset = (scaledContentSize - effectiveContainerSize) / 2f
        return value.coerceIn(-maxOffset, maxOffset)
    }

    val gestureModifier = if (enableZoom) {
        Modifier.pointerInput(enableZoom, zoomStateKey, minScale, maxScale, lockTapWhileZoomed) {
            coroutineScope {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val isDoubleTapCandidate = (downTime - lastTapTime) < 300L

                        var totalPan = Offset.Zero
                        var isSignificantMovement = false
                        var isTransforming = false
                        val touchSlop = viewConfiguration.touchSlop
                        var lastUpPosition = down.position

                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            totalPan += Offset(abs(panChange.x), abs(panChange.y))

                            if (zoomChange != 1f || totalPan.x > touchSlop || totalPan.y > touchSlop) {
                                isSignificantMovement = true
                            }

                            if (!isTransforming) {
                                if (zoomChange != 1f || scale > 1.05f) {
                                    isTransforming = true
                                }
                            }

                            if (isTransforming) {
                                val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                                val width = size.width.toFloat()
                                val height = size.height.toFloat()

                                scale = newScale
                                emitZoomState(scale)
                                offsetX = clampOffset(offsetX + panChange.x, width, scale)
                                offsetY = clampOffset(offsetY + panChange.y, height, scale, isDynamic = dynamicHeight)

                                event.changes.fastForEach { it.consume() }
                            }

                            val upChange = event.changes.firstOrNull { !it.pressed }
                            if (upChange != null) {
                                lastUpPosition = upChange.position
                            }
                        } while (event.changes.fastAny { it.pressed })

                        if (!isSignificantMovement) {
                            if (isDoubleTapCandidate) {
                                // Cycle: minScale → 2× → maxScale → minScale
                                val targetScale = when {
                                    scale > 2.05f -> minScale
                                    scale > 1.05f -> maxScale.coerceAtMost(4f)
                                    else -> 2f
                                }
                                if (targetScale <= minScale + ZOOM_STATE_EPSILON) {
                                    scale = minScale; offsetX = 0f; offsetY = 0f
                                } else {
                                    val width = size.width.toFloat()
                                    val height = size.height.toFloat()
                                    if (width > 0 && height > 0) {
                                        val deltaX = lastUpPosition.x - width / 2f
                                        val deltaY = lastUpPosition.y - height / 2f
                                        scale = targetScale
                                        offsetX = clampOffset(-deltaX * (targetScale - 1f), width, targetScale)
                                        offsetY = clampOffset(-deltaY * (targetScale - 1f), height, targetScale, isDynamic = dynamicHeight)
                                    } else {
                                        scale = targetScale
                                    }
                                }
                                emitZoomState(scale)
                                lastTapTime = 0L
                            } else {
                                lastTapTime = downTime
                                scope.launch {
                                    delay(200)
                                    if (lastTapTime == downTime) {
                                        if (shouldHandleTap(scale = scale, minScale = minScale, lockTapWhileZoomed = lockTapWhileZoomed)) {
                                            onTap?.invoke()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { onTap?.invoke() }
        )
    }

    val layoutModifier = if (enableZoom && dynamicHeight) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val currentScale = scale
            val scaledHeight = (placeable.height * currentScale).toInt()
            layout(placeable.width, scaledHeight) {
                // When scaling from TopCenter, the visual top stays at 0,
                // so we place the placeable at 0,0 and it will fill the scaledHeight naturally.
                placeable.placeRelative(0, 0)
            }
        }
    } else Modifier

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .clipToBounds()
            .then(layoutModifier)
            .then(gestureModifier),
        contentAlignment = Alignment.TopCenter // Align to top for dynamic height growth
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    // Key Fix: Use TopCenter origin for dynamic height so the image grows downwards
                    // and stays aligned with the layout-allocated space.
                    transformOrigin = if (dynamicHeight) TransformOrigin(0.5f, 0f) else TransformOrigin.Center
                }
        ) {
            content()
        }
    }
}
