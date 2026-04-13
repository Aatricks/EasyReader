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
}
