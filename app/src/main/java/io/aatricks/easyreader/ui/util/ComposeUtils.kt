package io.aatricks.easyreader.ui.util

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.aatricks.easyreader.data.model.ContentElement

fun Modifier.splitImageLayer(
    side: ContentElement.Image.Side,
    width: Int,
    height: Int
): Modifier {
    if (side == ContentElement.Image.Side.FULL) return this

    return this
        .graphicsLayer {
            clip = true
            translationX = if (side == ContentElement.Image.Side.LEFT) {
                size.width / 2
            } else {
                -size.width / 2
            }
        }
}

fun Modifier.imageAspectRatio(
    side: ContentElement.Image.Side,
    width: Int,
    height: Int
): Modifier {
    val aspectRatio = effectiveAspectRatio(side = side, width = width, height = height)
    return if (aspectRatio != null) this.aspectRatio(aspectRatio) else this
}
