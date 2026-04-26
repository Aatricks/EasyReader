package io.aatricks.easyreader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderContentAreaLifecycleTest {

    @Test
    fun `calculateReaderScrollSnapshot computes list metrics from current lazy state`() {
        val snapshot = calculateReaderScrollSnapshot(
            firstVisibleItemIndex = 5,
            firstVisibleItemMeasuredIndex = 5,
            firstVisibleItemScrollOffset = 120,
            canScrollForward = true,
            totalItemsCount = 8,
            viewportHeightPx = 900,
            firstVisibleItemSize = 300
        )

        requireNotNull(snapshot)
        assertEquals(5.4f, snapshot.scrollOffset, 0.0001f)
        assertEquals(10f, snapshot.maxScrollOffset, 0.0001f)
        assertEquals(3f, snapshot.viewportHeightInItems, 0.0001f)
        assertEquals(5, snapshot.index)
        assertEquals(120, snapshot.offset)
        assertEquals(300, snapshot.firstVisibleItemSize)
    }

    @Test
    fun `calculateReaderScrollSnapshot returns null when first item size is unstable`() {
        val snapshot = calculateReaderScrollSnapshot(
            firstVisibleItemIndex = 0,
            firstVisibleItemMeasuredIndex = 0,
            firstVisibleItemScrollOffset = 0,
            canScrollForward = true,
            totalItemsCount = 5,
            viewportHeightPx = 500,
            firstVisibleItemSize = 0
        )

        assertNull(snapshot)
    }

    @Test
    fun `flushReaderLifecycleProgress updates scroll before persist`() {
        val order = mutableListOf<String>()
        val snapshot = ReaderScrollSnapshot(
            scrollOffset = 2.5f,
            maxScrollOffset = 8f,
            viewportHeightInItems = 2f,
            index = 2,
            offset = 30,
            canScrollForward = true,
            firstVisibleItemSize = 400
        )

        flushReaderLifecycleProgress(
            snapshot = snapshot,
            updateScrollPosition = { order += "update" },
            persistProgress = { order += "persist" }
        )

        assertEquals(listOf("update", "persist"), order)
    }
}
