package io.aatricks.novelscraper.ui.util

import kotlin.math.roundToInt

fun normalizeRestoreOffset(offsetPx: Int, itemSizePx: Int): Float? {
    if (itemSizePx <= 0) return null
    return (offsetPx.toFloat() / itemSizePx.toFloat()).coerceIn(0f, 1f)
}

fun resolveRestoreOffset(
    savedOffsetPx: Int,
    savedOffsetFraction: Float?,
    itemSizePx: Int
): Int {
    if (itemSizePx > 0 && savedOffsetFraction != null) {
        return (itemSizePx * savedOffsetFraction.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(0, itemSizePx)
    }

    return savedOffsetPx.coerceAtLeast(0)
}
