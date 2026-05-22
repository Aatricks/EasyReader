package io.aatricks.easyreader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `local media state is empty for non-http images`() {
        assertEquals(
            "",
            readerImageLocalMediaState("file:///tmp/image.jpg") {
                error("Local file images should not probe the HTTP media cache")
            }
        )
    }

    @Test
    fun `local media state reports missing http cache file`() {
        assertEquals(
            "missing",
            readerImageLocalMediaState("https://example.com/image.jpg") {
                File("/tmp/easyreader-missing-test-image.jpg")
            }
        )
    }

    @Test
    fun `local media state includes file identity for existing cache file`() {
        val file = File.createTempFile("reader-image-view", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))

            assertEquals(
                "${file.absolutePath}:4:${file.lastModified()}",
                readerImageLocalMediaState("https://example.com/image.jpg") { file }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `request cache key changes with local media state and retry trigger`() {
        assertNull(readerImageRequestCacheKey("file:///tmp/image.jpg", "ignored", 10L))
        assertEquals(
            "https://example.com/image.jpg|missing|10",
            readerImageRequestCacheKey("https://example.com/image.jpg", "missing", 10L)
        )
        assertEquals(
            "https://example.com/image.jpg|/tmp/image.jpg:12:100|11",
            readerImageRequestCacheKey("https://example.com/image.jpg", "/tmp/image.jpg:12:100", 11L)
        )
    }

    @Test
    fun `only failed http images auto retry within attempt limit`() {
        assertTrue(shouldAutoRetryReaderImage(isError = true, imageUrl = "https://example.com/a.jpg", attemptCount = 0))
        assertFalse(shouldAutoRetryReaderImage(isError = false, imageUrl = "https://example.com/a.jpg", attemptCount = 0))
        assertFalse(shouldAutoRetryReaderImage(isError = true, imageUrl = "file:///tmp/a.jpg", attemptCount = 0))
        assertFalse(shouldAutoRetryReaderImage(isError = true, imageUrl = "https://example.com/a.jpg", attemptCount = 3))
    }
}
