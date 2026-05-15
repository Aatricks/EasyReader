package io.aatricks.easyreader.ui.util

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class FontFamilyMappingTest {

    @Test
    fun mapsSerif() {
        assertEquals(FontFamily.Serif, "Serif".toFontFamily())
    }

    @Test
    fun mapsMonospace() {
        assertEquals(FontFamily.Monospace, "Monospace".toFontFamily())
    }

    @Test
    fun mapsCursive() {
        assertEquals(FontFamily.Cursive, "Cursive".toFontFamily())
    }

    @Test
    fun defaultsToSansSerif() {
        assertEquals(FontFamily.SansSerif, "Default".toFontFamily())
        assertEquals(FontFamily.SansSerif, "Unknown".toFontFamily())
        assertEquals(FontFamily.SansSerif, null.toFontFamily())
    }
}
