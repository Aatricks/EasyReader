package io.aatricks.easyreader.data.repository.content

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class OfflineProgressGate(
    val totalImages: Int,
    val clock: () -> Long = { System.currentTimeMillis() },
    private val onEmit: suspend (count: Int, isTerminal: Boolean) -> Unit
) {
    private companion object {
        private const val CADENCE_COUNT_DELTA = 5
        private const val CADENCE_TIME_INTERVAL_MS = 250L
    }

    private val mutex = Mutex()
    private val lastEmittedCount = AtomicInteger(-1)
    private var lastEmittedTimeMs: Long = 0L

    suspend fun emitInitial(completedCount: Int) {
        checkAndEmit(completedCount, force = true, isTerminal = false)
    }

    suspend fun onImageCompleted(completedCount: Int) {
        checkAndEmit(completedCount, force = false, isTerminal = false)
    }

    suspend fun emitTerminal(completedCount: Int) {
        checkAndEmit(completedCount, force = true, isTerminal = true)
    }

    private suspend fun checkAndEmit(completedCount: Int, force: Boolean, isTerminal: Boolean) {
        mutex.withLock {
            val prevCount = lastEmittedCount.get()
            val now = clock()
            val shouldEmit = force ||
                completedCount - prevCount >= CADENCE_COUNT_DELTA ||
                (now - lastEmittedTimeMs >= CADENCE_TIME_INTERVAL_MS)
            if (shouldEmit) {
                lastEmittedCount.set(completedCount)
                lastEmittedTimeMs = now
                onEmit(completedCount, isTerminal)
            }
        }
    }
}
