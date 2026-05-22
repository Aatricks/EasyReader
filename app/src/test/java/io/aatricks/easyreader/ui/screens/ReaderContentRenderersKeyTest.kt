package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContentRenderersKeyTest {

    @Test
    fun `stableContentElementKey uses image url identity`() {
        val pageUrl = "https://example.com/chapter-1"
        val image = ContentElement.Image("https://cdn.example.com/panel-1.jpg")

        val first = stableContentElementKey(pageUrl, 2, image)
        val second = stableContentElementKey(pageUrl, 7, image)

        assertEquals("img:https://cdn.example.com/panel-1.jpg", first)
        assertEquals(first, second)
    }

    @Test
    fun `stableContentElementKey uses grouped image urls identity`() {
        val group = ContentElement.ImageGroup(
            listOf(
                ContentElement.Image("https://cdn.example.com/g1.jpg"),
                ContentElement.Image("https://cdn.example.com/g2.jpg")
            )
        )

        val key = stableContentElementKey("https://example.com/chapter-1", 3, group)

        assertEquals("group:https://cdn.example.com/g1.jpg|https://cdn.example.com/g2.jpg", key)
    }

    @Test
    fun `percent restore fallback is skipped for precise restores`() {
        assertEquals(false, shouldRunPercentRestoreFallback(isPreciseRestore = true, targetFraction = 0.4f))
        assertEquals(false, shouldRunPercentRestoreFallback(isPreciseRestore = false, targetFraction = 0f))
        assertEquals(true, shouldRunPercentRestoreFallback(isPreciseRestore = false, targetFraction = 0.4f))
    }
}
