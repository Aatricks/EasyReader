package io.aatricks.easyreader.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderRestoreTest {

    @Test
    fun `resolveRestoreOffset prefers normalized fraction when item size is known`() {
        assertEquals(
            70,
            resolveRestoreOffset(
                savedOffsetPx = 140,
                savedOffsetFraction = 0.35f,
                itemSizePx = 200
            )
        )
    }

    @Test
    fun `resolveRestoreOffset falls back to saved pixels when item size is unknown`() {
        assertEquals(
            140,
            resolveRestoreOffset(
                savedOffsetPx = 140,
                savedOffsetFraction = 0.35f,
                itemSizePx = 0
            )
        )
    }

    @Test
    fun `resolveRestoreOffset clamps normalized fraction into item bounds`() {
        assertEquals(
            200,
            resolveRestoreOffset(
                savedOffsetPx = 10,
                savedOffsetFraction = 1.5f,
                itemSizePx = 200
            )
        )
    }
}
