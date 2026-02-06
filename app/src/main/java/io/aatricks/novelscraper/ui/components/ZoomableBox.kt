package io.aatricks.novelscraper.ui.components

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

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 3f,
    enableZoom: Boolean = false,
    dynamicHeight: Boolean = false,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var scale by remember { mutableFloatStateOf(minScale) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val scope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }

    fun clampOffset(value: Float, contentSize: Float, currentScale: Float, isDynamic: Boolean = false): Float {
        val scaledContentSize = contentSize * currentScale
        // If height is dynamic, we don't allow vertical panning within the box because the list handles it
        val effectiveContainerSize = if (isDynamic) scaledContentSize else contentSize

        if (scaledContentSize <= effectiveContainerSize) return 0f
        val maxOffset = (scaledContentSize - effectiveContainerSize) / 2f
        return value.coerceIn(-maxOffset, maxOffset)
    }

    val gestureModifier = if (enableZoom) {
        Modifier.pointerInput(Unit) {
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
                                offsetX = clampOffset(offsetX + panChange.x, width, scale)

                                if (!dynamicHeight) {
                                    offsetY = clampOffset(offsetY + panChange.y, height, scale)
                                } else {
                                    offsetY = 0f
                                }

                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            }

                            val upChange = event.changes.firstOrNull { !it.pressed }
                            if (upChange != null) {
                                lastUpPosition = upChange.position
                            }
                        } while (event.changes.fastAny { it.pressed })

                        if (!isSignificantMovement) {
                            if (isDoubleTapCandidate) {
                                if (scale > 1.05f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    val targetScale = 2.5f
                                    val width = size.width.toFloat()
                                    val height = size.height.toFloat()
                                    if (width > 0) {
                                        val deltaX = lastUpPosition.x - width / 2f
                                        scale = targetScale
                                        offsetX = clampOffset(-deltaX * (targetScale - 1f), width, targetScale)
                                        offsetY = 0f
                                    } else {
                                        scale = targetScale
                                    }
                                }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = downTime
                                scope.launch {
                                    delay(200)
                                    if (lastTapTime == downTime) {
                                        onTap?.invoke()
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
