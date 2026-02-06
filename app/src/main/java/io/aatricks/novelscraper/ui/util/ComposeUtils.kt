package io.aatricks.novelscraper.ui.util

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.aatricks.novelscraper.data.model.ContentElement

fun Modifier.splitImageLayer(
    side: ContentElement.Image.Side,
    width: Int,
    height: Int
): Modifier {
    if (side == ContentElement.Image.Side.FULL) return this

    return this
        .graphicsLayer {
            clip = true
            scaleX = 2f
            scaleY = 1f
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
    return if (width > 0 && height > 0) {
        val effectiveWidth = if (side != ContentElement.Image.Side.FULL) width.toFloat() / 2f else width.toFloat()
        this.aspectRatio(effectiveWidth / height.toFloat())
    } else {
        this
    }
}
