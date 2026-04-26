package io.aatricks.easyreader.ui.util

import io.aatricks.easyreader.data.model.ContentElement

data class ImageDimensions(
    val width: Int,
    val height: Int
)

fun effectiveImageDimensions(
    declaredWidth: Int,
    declaredHeight: Int,
    resolvedWidth: Int = 0,
    resolvedHeight: Int = 0
): ImageDimensions? {
    return when {
        declaredWidth > 0 && declaredHeight > 0 -> ImageDimensions(declaredWidth, declaredHeight)
        resolvedWidth > 0 && resolvedHeight > 0 -> ImageDimensions(resolvedWidth, resolvedHeight)
        else -> null
    }
}

fun effectiveAspectRatio(
    side: ContentElement.Image.Side,
    width: Int,
    height: Int
): Float? {
    if (width <= 0 || height <= 0) return null
    val effectiveWidth = if (side != ContentElement.Image.Side.FULL) width.toFloat() / 2f else width.toFloat()
    return effectiveWidth / height.toFloat()
}
