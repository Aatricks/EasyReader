package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 3f,
    enableZoom: Boolean = false,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var scale by remember { mutableFloatStateOf(minScale) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(value: Float, contentSize: Float, currentScale: Float): Float {
        val scaledContentSize = contentSize * currentScale
        if (scaledContentSize <= contentSize) return 0f

        val maxOffset = (scaledContentSize - contentSize) / 2f
        return value.coerceIn(-maxOffset, maxOffset)
    }

    if (enableZoom) {
        Box(
            modifier = modifier
                .onSizeChanged { size = it }
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > minScale) {
                                scale = minScale
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2f
                            }
                        },
                        onTap = { onTap?.invoke() }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.fastAny { it.isConsumed }
                            if (!canceled) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val isZooming = zoomChange != 1f

                                if (isZooming || scale > minScale) {
                                    val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                                    val isScaling = newScale != scale
                                    scale = newScale

                                    val width = size.width.toFloat()
                                    val height = size.height.toFloat()

                                    // Apply pan relative to current scale
                                    val targetX = offsetX + panChange.x
                                    val targetY = offsetY + panChange.y

                                    val newOffsetX = clampOffset(targetX, width, scale)
                                    val newOffsetY = clampOffset(targetY, height, scale)

                                    val hasPannedX = abs(newOffsetX - offsetX) > 0.1f
                                    val hasPannedY = abs(newOffsetY - offsetY) > 0.1f

                                    offsetX = newOffsetX
                                    offsetY = newOffsetY

                                    // Consumption Logic:
                                    // 1. If we are actively scaling (pinching), consume.
                                    // 2. If we effectively panned (moved within bounds), consume.
                                    // 3. If we tried to pan but were clamped (hit edge), DO NOT consume (let parent scroll).

                                    if (isScaling || isZooming || hasPannedX || hasPannedY) {
                                        event.changes.fastForEach {
                                            if (it.positionChanged()) it.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.fastAny { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { onTap?.invoke() })
            },
            content = content
        )
    }
}
