package io.aatricks.easyreader.ui.screens.reader

internal fun shouldRunPercentRestoreFallback(
    isPreciseRestore: Boolean,
    targetFraction: Float?
): Boolean = !isPreciseRestore || targetFraction == null || targetFraction == 0f
