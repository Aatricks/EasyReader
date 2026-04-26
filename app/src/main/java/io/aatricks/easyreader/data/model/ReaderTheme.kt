package io.aatricks.easyreader.data.model

import androidx.compose.ui.graphics.Color

import kotlinx.serialization.Serializable

/**
 * Themes for the novel reader
 */
@Serializable
enum class ReaderTheme(
    val backgroundColor: Color,
    val textColor: Color,
    val displayName: String
) {
    DARK(Color(0xFF121212), Color(0xFFE0E0E0), "Dark"),
    LIGHT(Color(0xFFFFFFFF), Color(0xFF121212), "Light"),
    SEPIA(Color(0xFFF4ECD8), Color(0xFF5B4636), "Sepia"),
    PAPER(Color(0xFFFDF6E3), Color(0xFF657B83), "Paper"),
    OLED(Color(0xFF000000), Color(0xFFFFFFFF), "OLED")
}
