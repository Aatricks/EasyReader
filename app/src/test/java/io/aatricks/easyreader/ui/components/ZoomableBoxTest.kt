package io.aatricks.easyreader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomableBoxTest {

    @Test
    fun `isZoomed returns true only after epsilon threshold`() {
        assertFalse(isZoomed(scale = 1.0f, minScale = 1.0f))
        assertFalse(isZoomed(scale = 1.04f, minScale = 1.0f))
        assertTrue(isZoomed(scale = 1.06f, minScale = 1.0f))
    }

    @Test
    fun `shouldHandleTap locks taps while zoomed when lock is enabled`() {
        assertFalse(shouldHandleTap(scale = 1.2f, minScale = 1.0f, lockTapWhileZoomed = true))
        assertTrue(shouldHandleTap(scale = 1.0f, minScale = 1.0f, lockTapWhileZoomed = true))
        assertTrue(shouldHandleTap(scale = 1.2f, minScale = 1.0f, lockTapWhileZoomed = false))
    }
}
