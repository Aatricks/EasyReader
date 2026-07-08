package io.aatricks.easyreader.ui.screens.reader

private const val LEFT_ZONE_THRESHOLD = 0.25f
private const val RIGHT_ZONE_THRESHOLD = 0.75f

enum class ReaderTapAction {
    TOGGLE_CONTROLS,
    PAGE_FORWARD,
    PAGE_BACK
}

fun resolveReaderTapAction(
    xFraction: Float,
    isPaged: Boolean,
    isRtl: Boolean
): ReaderTapAction {
    if (!isPaged) {
        return ReaderTapAction.TOGGLE_CONTROLS
    }
    return when {
        xFraction < LEFT_ZONE_THRESHOLD -> {
            if (isRtl) ReaderTapAction.PAGE_FORWARD else ReaderTapAction.PAGE_BACK
        }
        xFraction > RIGHT_ZONE_THRESHOLD -> {
            if (isRtl) ReaderTapAction.PAGE_BACK else ReaderTapAction.PAGE_FORWARD
        }
        else -> ReaderTapAction.TOGGLE_CONTROLS
    }
}
