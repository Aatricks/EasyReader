package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageDimensionManagerTest {

    private class RecordingApplier {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        fun apply(updates: Map<String, Pair<Int, Int>>) {
            batches.add(updates)
        }
    }

    @Test
    fun `persist stores dimensions and applies content rebuild once`() = runTest {
        val applier = RecordingApplier()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository(), applier::apply)

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(800 to 1200, manager.resolvedDimensions("http://img/1.jpg"))
        assertEquals(listOf(mapOf("http://img/1.jpg" to (800 to 1200))), applier.batches)
    }

    @Test
    fun `dimensionState returns the same observable instance and tracks persists`() = runTest {
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) {}

        val state = manager.dimensionState("http://img/1.jpg")
        assertSame(state, manager.dimensionState("http://img/1.jpg"))
        assertNull(state.value)

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(800 to 1200, state.value)
    }

    @Test
    fun `duplicate persist of identical dimensions is a no-op`() = runTest {
        val applier = RecordingApplier()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository(), applier::apply)

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()
        // Re-entry of a recycled item re-reports the same dimensions.
        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(1, applier.batches.size)
    }

    @Test
    fun `changed dimensions for the same url go through`() = runTest {
        val applier = RecordingApplier()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository(), applier::apply)

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()
        manager.persistImageDimensions("http://img/1.jpg", 900, 1600)
        advanceUntilIdle()

        assertEquals(900 to 1600, manager.resolvedDimensions("http://img/1.jpg"))
        assertEquals(2, applier.batches.size)
        assertEquals(mapOf("http://img/1.jpg" to (900 to 1600)), applier.batches[1])
    }

    @Test
    fun `blank url and non-positive dimensions are rejected`() = runTest {
        val applier = RecordingApplier()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository(), applier::apply)

        manager.persistImageDimensions("", 800, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 0, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 800, -1)
        advanceUntilIdle()

        assertNull(manager.resolvedDimensions("http://img/1.jpg"))
        assertEquals(0, applier.batches.size)
    }
}
