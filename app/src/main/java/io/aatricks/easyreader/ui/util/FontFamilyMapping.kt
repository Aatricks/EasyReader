package io.aatricks.easyreader.ui.util

import androidx.compose.ui.text.font.FontFamily

fun String?.toFontFamily(): FontFamily = when (this) {
    "Serif" -> FontFamily.Serif
    "Monospace" -> FontFamily.Monospace
    "Cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
