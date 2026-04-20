package io.aatricks.novelscraper.ui.components

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

    @Test
    fun `reader sample size increases for oversized images`() {
        val sampleSize = calculateReaderInSampleSize(
            sourceWidth = 4000,
            sourceHeight = 6000,
            targetWidth = 1000,
            targetHeight = 1500
        )

        assertTrue(sampleSize > 1)
    }

    @Test
    fun `reader sample size stays at one for small images`() {
        val sampleSize = calculateReaderInSampleSize(
            sourceWidth = 800,
            sourceHeight = 1200,
            targetWidth = 1000,
            targetHeight = 1500
        )

        assertTrue(sampleSize == 1)
    }
}
