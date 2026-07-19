package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingSessionTracker(
    private val readingSessionDao: ReadingSessionDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    @Inject
    constructor(readingSessionDao: ReadingSessionDao) : this(readingSessionDao, { System.currentTimeMillis() })

    private var currentNovelKey: String? = null
    private var startedAt: Long = 0L
    private var endedAt: Long = 0L
    private var lastInteractionTime: Long? = null
    private var activeMillis: Long = 0L
    private var chaptersCompleted: Int = 0
    private val completedChapters = mutableSetOf<String>()

    private val _completionEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val completionEvents: SharedFlow<Int> = _completionEvents

    val isTracking: Boolean
        get() = currentNovelKey != null

    suspend fun start(novelKey: String) {
        if (novelKey.isBlank()) return
        val current = currentNovelKey
        if (current != null && current != novelKey) {
            stop()
        }
        if (currentNovelKey == null) {
            val now = clock()
            currentNovelKey = novelKey
            startedAt = now
            endedAt = now
            lastInteractionTime = now
            activeMillis = 0L
            chaptersCompleted = 0
            completedChapters.clear()
        }
    }

    fun onInteraction() {
        if (currentNovelKey == null) return
        val now = clock()
        val last = lastInteractionTime ?: now
        val gap = (now - last).coerceAtLeast(0L)
        val increment = kotlin.math.min(gap, IDLE_TIMEOUT_MILLIS)
        activeMillis += increment
        lastInteractionTime = now
        endedAt = now
    }

    fun onChapterCompleted(chapterUrl: String? = null) {
        if (currentNovelKey == null) return
        if (chapterUrl.isNullOrBlank()) {
            chaptersCompleted++
            _completionEvents.tryEmit(chaptersCompleted)
        } else if (completedChapters.add(chapterUrl)) {
            chaptersCompleted++
            _completionEvents.tryEmit(chaptersCompleted)
        }
    }

    fun stop() {
        val novelKey = currentNovelKey ?: return
        val now = clock()
        val last = lastInteractionTime ?: now
        val gap = (now - last).coerceAtLeast(0L)
        val totalActive = activeMillis + kotlin.math.min(gap, IDLE_TIMEOUT_MILLIS)
        val finalEndedAt = now

        if (totalActive >= MIN_ACTIVE_MILLIS_TO_PERSIST) {
            val entity = ReadingSessionEntity(
                novelKey = novelKey,
                startedAt = startedAt,
                endedAt = finalEndedAt,
                activeMillis = totalActive,
                chaptersCompleted = chaptersCompleted,
                seeded = false
            )
            persistScope.launch {
                readingSessionDao.insert(entity)
            }
        }
        resetState()
    }

    private fun resetState() {
        currentNovelKey = null
        startedAt = 0L
        endedAt = 0L
        lastInteractionTime = null
        activeMillis = 0L
        chaptersCompleted = 0
        completedChapters.clear()
    }

    companion object {
        private const val IDLE_TIMEOUT_MILLIS = 150_000L
        private const val MIN_ACTIVE_MILLIS_TO_PERSIST = 10_000L
    }
}
