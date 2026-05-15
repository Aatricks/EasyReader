package io.aatricks.easyreader.ui.screens

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
        // 6px used to cross the old 5px threshold; new slop is 10px so this stays below.
        assertFalse(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 0f, y = 6f),
                hasHandledCurrentGesture = false
            )
        )
    }

    @Test
    fun `large vertical deltas dispatch reader scroll start once`() {
        assertTrue(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 0f, y = 12f),
                hasHandledCurrentGesture = false
            )
        )
        assertFalse(
            shouldDispatchReaderScrollStart(
                available = Offset(x = 0f, y = 12f),
                hasHandledCurrentGesture = true
            )
        )
    }

    @Test
    fun `large horizontal deltas also dispatch reader scroll start`() {
        assertTrue(
            shouldDispatchReaderScrollStart(
                available = Offset(x = -14f, y = 0f),
                hasHandledCurrentGesture = false
            )
        )
    }
}
