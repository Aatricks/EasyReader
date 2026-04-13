package io.aatricks.novelscraper.ui.util

import io.aatricks.novelscraper.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderImageSizingTest {

    @Test
    fun `declared dimensions win over resolved dimensions`() {
        val effective = effectiveImageDimensions(
            declaredWidth = 1200,
            declaredHeight = 1800,
            resolvedWidth = 800,
            resolvedHeight = 1200
        )

        assertEquals(ImageDimensions(width = 1200, height = 1800), effective)
    }

    @Test
    fun `resolved dimensions are used when declared dimensions are missing`() {
        val effective = effectiveImageDimensions(
            declaredWidth = 0,
            declaredHeight = 0,
            resolvedWidth = 800,
            resolvedHeight = 1200
        )

        assertEquals(ImageDimensions(width = 800, height = 1200), effective)
    }

    @Test
    fun `partial dimensions do not produce aspect ratio`() {
        assertNull(
            effectiveAspectRatio(
                side = ContentElement.Image.Side.FULL,
                width = 800,
                height = 0
            )
        )
    }

    @Test
    fun `split images use half width for aspect ratio`() {
        val aspectRatio = effectiveAspectRatio(
            side = ContentElement.Image.Side.LEFT,
            width = 1200,
            height = 1800
        )

        assertEquals(600f / 1800f, aspectRatio ?: 0f, 0.0001f)
    }
}
