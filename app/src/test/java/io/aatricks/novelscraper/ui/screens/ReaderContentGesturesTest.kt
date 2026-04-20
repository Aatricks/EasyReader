package io.aatricks.novelscraper.ui.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentGesturesTest {

    @Test
    fun `small deltas do not dispatch reader scroll start`() {
        assertFalse(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 3f, y = 4f),
                hasHandledCurrentGesture = false
            )
        )
    }

    @Test
    fun `large vertical deltas dispatch reader scroll start once`() {
        assertTrue(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 0f, y = 6f),
                hasHandledCurrentGesture = false
            )
        )
        assertFalse(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 0f, y = 6f),
                hasHandledCurrentGesture = true
            )
        )
    }

    @Test
    fun `large horizontal deltas also dispatch reader scroll start`() {
        assertTrue(
            shouldDispatchReaderScrollStart(
                available = Offset(x = -7f, y = 0f),
                hasHandledCurrentGesture = false
            )
        )
    }
}
