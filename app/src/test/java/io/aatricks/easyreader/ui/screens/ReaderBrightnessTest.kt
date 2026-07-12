package io.aatricks.easyreader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBrightnessTest {

    @Test
    fun `brightness maps to clamped black overlay alpha`() {
        assertEquals(0f, brightnessOverlayAlpha(1f), 0.001f)
        assertEquals(0.5f, brightnessOverlayAlpha(0.5f), 0.001f)
        assertEquals(0.9f, brightnessOverlayAlpha(0.1f), 0.001f)
        assertEquals(0f, brightnessOverlayAlpha(2f), 0.001f)
        assertEquals(0.9f, brightnessOverlayAlpha(0f), 0.001f)
    }
}
