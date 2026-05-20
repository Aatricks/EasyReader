package io.aatricks.easyreader.ui.components

import org.junit.Assert.assertEquals
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
    fun `reader image referer source falls back to image url`() {
        assertEquals(
            "https://www.mangabats.com/manga/a-fortune-telling-princess/chapter-115",
            readerImageRefererSource(
                imageUrl = "https://img-r1.2xstorage.com/a-fortune-telling-princess/115/0.webp",
                pageUrl = "https://www.mangabats.com/manga/a-fortune-telling-princess/chapter-115"
            )
        )
        assertEquals(
            "https://img-r1.2xstorage.com/a-fortune-telling-princess/115/0.webp",
            readerImageRefererSource(
                imageUrl = "https://img-r1.2xstorage.com/a-fortune-telling-princess/115/0.webp",
                pageUrl = ""
            )
        )
    }
}
