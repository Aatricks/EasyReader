package io.aatricks.easyreader.ui.util

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Modifier
import io.aatricks.easyreader.data.model.ContentElement

fun Modifier.imageAspectRatio(
    side: ContentElement.Image.Side,
    width: Int,
    height: Int
): Modifier {
    val aspectRatio = effectiveAspectRatio(side = side, width = width, height = height)
    return if (aspectRatio != null) this.aspectRatio(aspectRatio) else this
}
