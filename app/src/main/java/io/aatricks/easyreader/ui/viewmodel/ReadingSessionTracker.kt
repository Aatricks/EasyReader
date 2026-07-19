package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Accrues active reading time for the current series.
 *
 * Accrual model: time is credited continuously up to [IDLE_TIMEOUT_MILLIS] past the last
 * interaction, so quietly reading a static page still counts, while a reader left open on a
 * shelf stops accruing five minutes after the last touch. A periodic checkpoint persists the
 * running session so totals stay live in the UI and survive process death; the row is written
 * once and then updated in place. After [stop] the next interaction transparently starts a new
 * session for the same series, which keeps tracking alive across pause/resume cycles that
 * never re-trigger a content load.
 */
@Singleton
class ReadingSessionTracker(
    private val readingSessionDao: ReadingSessionDao,
    private val preferencesManager: PreferencesManager,
    private val trackerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(readingSessionDao: ReadingSessionDao, preferencesManager: PreferencesManager) : this(
        readingSessionDao,
        preferencesManager,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { System.currentTimeMillis() }
    )

    private val lock = Any()

    private var currentNovelKey: String? = null
    private var lastNovelKey: String? = null
    private var startedAt: Long = 0L
    private var lastInteractionTime: Long = 0L
    private var lastAccrualTime: Long = 0L
    private var activeMillis: Long = 0L
    private var chaptersCompleted: Int = 0
    private val completedChapters = mutableSetOf<String>()
    private var persistedRowId: Long? = null
    private var checkpointJob: Job? = null

    private val _completionEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val completionEvents: SharedFlow<Int> = _completionEvents

    val isTracking: Boolean
        get() = synchronized(lock) { currentNovelKey != null }

    fun start(novelKey: String) {
        if (novelKey.isBlank() || !preferencesManager.scrollGamificationEnabled) return
        val shouldRestart = synchronized(lock) {
            currentNovelKey != null && currentNovelKey != novelKey
        }
        if (shouldRestart) stop()
        synchronized(lock) {
            if (currentNovelKey != null) return
            val now = clock()
            currentNovelKey = novelKey
            lastNovelKey = novelKey
            startedAt = now
            lastInteractionTime = now
            lastAccrualTime = now
            activeMillis = 0L
            chaptersCompleted = 0
            completedChapters.clear()
            persistedRowId = null
        }
        checkpointJob = trackerScope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MILLIS)
                checkpoint()
            }
        }
    }

    /**
     * Any reader activity: scrolling, page turns, progress writes. Restarts a stopped
     * session for the last-known series so tracking survives pause/resume.
     */
    fun onInteraction() {
        val restartKey = synchronized(lock) {
            if (currentNovelKey == null) lastNovelKey else null
        }
        if (restartKey != null) {
            start(restartKey)
            return
        }
        synchronized(lock) {
            if (currentNovelKey == null) return
            val now = clock()
            accrueLocked(now)
            lastInteractionTime = now
            lastAccrualTime = now
        }
    }

    fun onChapterCompleted(chapterUrl: String? = null) {
        val emitted = synchronized(lock) {
            if (currentNovelKey == null) return
            val now = clock()
            accrueLocked(now)
            lastInteractionTime = now
            lastAccrualTime = now
            val newlyCounted = chapterUrl.isNullOrBlank() || completedChapters.add(chapterUrl)
            if (newlyCounted) {
                chaptersCompleted++
                chaptersCompleted
            } else {
                null
            }
        }
        if (emitted != null) _completionEvents.tryEmit(emitted)
    }

    fun stop() {
        checkpointJob?.cancel()
        checkpointJob = null
        val snapshot = synchronized(lock) {
            val novelKey = currentNovelKey ?: return
            accrueLocked(clock())
            val entity = buildEntityLocked(novelKey)
            val rowId = persistedRowId
            resetLocked()
            entity to rowId
        }
        persist(snapshot.first, snapshot.second, sessionStartedAt = snapshot.first.startedAt, live = false)
    }

    /** Accrues pending time and persists the running session so totals stay live. */
    private fun checkpoint() {
        val snapshot = synchronized(lock) {
            val novelKey = currentNovelKey ?: return
            accrueLocked(clock())
            buildEntityLocked(novelKey) to persistedRowId
        }
        persist(snapshot.first, snapshot.second, sessionStartedAt = snapshot.first.startedAt, live = true)
    }

    /** Credits time since the last accrual, capped at the idle window past the last interaction. */
    private fun accrueLocked(now: Long) {
        val cutoff = minOf(now, lastInteractionTime + IDLE_TIMEOUT_MILLIS)
        if (cutoff > lastAccrualTime) {
            activeMillis += cutoff - lastAccrualTime
            lastAccrualTime = cutoff
        }
    }

    private fun buildEntityLocked(novelKey: String): ReadingSessionEntity = ReadingSessionEntity(
        novelKey = novelKey,
        startedAt = startedAt,
        endedAt = lastAccrualTime,
        activeMillis = activeMillis,
        chaptersCompleted = chaptersCompleted,
        seeded = false
    )

    private fun resetLocked() {
        currentNovelKey = null
        startedAt = 0L
        lastInteractionTime = 0L
        lastAccrualTime = 0L
        activeMillis = 0L
        chaptersCompleted = 0
        completedChapters.clear()
        persistedRowId = null
    }

    private fun persist(entity: ReadingSessionEntity, rowId: Long?, sessionStartedAt: Long, live: Boolean) {
        if (entity.activeMillis < MIN_ACTIVE_MILLIS_TO_PERSIST) return
        trackerScope.launch {
            if (rowId == null) {
                val newId = readingSessionDao.insert(entity)
                if (live) {
                    synchronized(lock) {
                        // Adopt the row only if this session is still the running one
                        if (currentNovelKey == entity.novelKey && startedAt == sessionStartedAt) {
                            persistedRowId = newId
                        }
                    }
                }
            } else {
                readingSessionDao.updateSessionProgress(
                    id = rowId,
                    endedAt = entity.endedAt,
                    activeMillis = entity.activeMillis,
                    chaptersCompleted = entity.chaptersCompleted
                )
            }
        }
    }

    companion object {
        // Generous window: quietly reading a long static page must still count
        private const val IDLE_TIMEOUT_MILLIS = 300_000L
        private const val CHECKPOINT_INTERVAL_MILLIS = 30_000L
        private const val MIN_ACTIVE_MILLIS_TO_PERSIST = 10_000L
    }
}
