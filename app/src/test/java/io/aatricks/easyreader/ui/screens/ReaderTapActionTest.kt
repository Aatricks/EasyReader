package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.ui.screens.reader.ReaderTapAction
import io.aatricks.easyreader.ui.screens.reader.resolveReaderTapAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapActionTest {

    @Test
    fun `scroll mode always returns TOGGLE_CONTROLS`() {
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.1f, isPaged = false, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.5f, isPaged = false, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.9f, isPaged = false, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.1f, isPaged = false, isRtl = true))
    }

    @Test
    fun `paged LTR zones`() {
        assertEquals(ReaderTapAction.PAGE_BACK, resolveReaderTapAction(0.24f, isPaged = true, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.25f, isPaged = true, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.50f, isPaged = true, isRtl = false))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.75f, isPaged = true, isRtl = false))
        assertEquals(ReaderTapAction.PAGE_FORWARD, resolveReaderTapAction(0.76f, isPaged = true, isRtl = false))
    }

    @Test
    fun `paged RTL zones`() {
        assertEquals(ReaderTapAction.PAGE_FORWARD, resolveReaderTapAction(0.24f, isPaged = true, isRtl = true))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.25f, isPaged = true, isRtl = true))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.50f, isPaged = true, isRtl = true))
        assertEquals(ReaderTapAction.TOGGLE_CONTROLS, resolveReaderTapAction(0.75f, isPaged = true, isRtl = true))
        assertEquals(ReaderTapAction.PAGE_BACK, resolveReaderTapAction(0.76f, isPaged = true, isRtl = true))
    }
}
