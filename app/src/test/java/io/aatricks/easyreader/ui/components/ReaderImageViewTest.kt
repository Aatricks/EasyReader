package io.aatricks.easyreader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageViewTest {

    @Test
    fun `non zoom images use lightweight container`() {
        assertTrue(shouldUseLightweightImageContainer(enableZoom = false))
    }

    @Test
    fun `zoom enabled images keep zoomable container`() {
        assertFalse(shouldUseLightweightImageContainer(enableZoom = true))
    }

    @Test
    fun `lightweight scroll images skip animated loading ui`() {
        assertFalse(shouldUseAnimatedImageLoadingUi(enableZoom = false, isCached = false))
    }

    @Test
    fun `zoom images keep animated loading ui when uncached`() {
        assertTrue(shouldUseAnimatedImageLoadingUi(enableZoom = true, isCached = false))
    }

    @Test
    fun `cached zoom images skip animated loading ui`() {
        assertFalse(shouldUseAnimatedImageLoadingUi(enableZoom = true, isCached = true))
    }

    @Test
    fun `scroll path images are eligible for subsampling`() {
        assertTrue(shouldSubsampleReaderImage(enableZoom = false, dynamicHeight = false))
        assertFalse(shouldSubsampleReaderImage(enableZoom = true, dynamicHeight = false))
        assertFalse(shouldSubsampleReaderImage(enableZoom = false, dynamicHeight = true))
    }
}
