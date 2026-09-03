package io.aatricks.easyreader.ui.components

import androidx.compose.ui.geometry.Offset
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

    @Test
    fun `pinch and multi-finger gestures are always consumed`() {
        assertTrue(
            consumesZoomDrag(
                pointerCount = 2,
                zoomChange = 1.2f,
                requested = Offset(0f, 40f),
                applied = Offset.Zero
            )
        )
        assertTrue(
            consumesZoomDrag(
                pointerCount = 2,
                zoomChange = 1f,
                requested = Offset(0f, 40f),
                applied = Offset.Zero
            )
        )
    }

    @Test
    fun `one finger keeps the drag while the zoomed content still moves`() {
        assertTrue(
            consumesZoomDrag(
                pointerCount = 1,
                zoomChange = 1f,
                requested = Offset(0f, 40f),
                applied = Offset(0f, 40f)
            )
        )
    }

    @Test
    fun `one finger releases the drag once the pan is pinned on the dragged axis`() {
        // Vertical drag, vertical pan clamped at its bound -> the LazyColumn must get it.
        assertFalse(
            consumesZoomDrag(
                pointerCount = 1,
                zoomChange = 1f,
                requested = Offset(2f, 40f),
                applied = Offset(2f, 0f)
            )
        )
        // Horizontal drag still panning sideways stays with the zoom box.
        assertTrue(
            consumesZoomDrag(
                pointerCount = 1,
                zoomChange = 1f,
                requested = Offset(40f, 2f),
                applied = Offset(40f, 0f)
            )
        )
    }

    @Test
    fun `a motionless tap is never consumed so it reaches the tap handler`() {
        assertFalse(
            consumesZoomDrag(
                pointerCount = 1,
                zoomChange = 1f,
                requested = Offset.Zero,
                applied = Offset.Zero
            )
        )
    }
}
