package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.local.SessionTotals
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ReadingSessionTrackerTest {

    private lateinit var fakeDao: FakeReadingSessionDao
    private var currentTime: Long = 0L

    /** Tracker whose checkpoint ticker and persistence run on the test scheduler. */
    private fun TestScope.buildTracker(enabled: Boolean = true): ReadingSessionTracker {
        fakeDao = FakeReadingSessionDao()
        currentTime = 0L
        val prefs = mock<PreferencesManager> {
            on { scrollGamificationEnabled } doReturn enabled
        }
        return ReadingSessionTracker(
            readingSessionDao = fakeDao,
            preferencesManager = prefs,
            trackerScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            clock = { currentTime }
        )
    }

    @Test
    fun `continuous reading with sparse interactions is fully credited`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)

        // Interactions four minutes apart: inside the five-minute idle window, all time counts
        currentTime = FOUR_MINUTES
        tracker.onInteraction()
        currentTime = FOUR_MINUTES * 2
        tracker.onInteraction()

        tracker.stop()
        runCurrent()

        assertEquals(FOUR_MINUTES * 2, fakeDao.sessions.single().activeMillis)
        assertEquals(0L, fakeDao.sessions.single().startedAt)
        assertEquals(FOUR_MINUTES * 2, fakeDao.sessions.single().endedAt)
    }

    @Test
    fun `idle time beyond the window is not credited`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)

        // Walk away for twenty minutes, then come back and touch the screen
        currentTime = TWENTY_MINUTES
        tracker.onInteraction()
        currentTime = TWENTY_MINUTES + ONE_MINUTE
        tracker.stop()
        runCurrent()

        // Only the five-minute idle window plus the final minute counts
        assertEquals(IDLE_WINDOW + ONE_MINUTE, fakeDao.sessions.single().activeMillis)
    }

    @Test
    fun `checkpoint persists a live session and updates it in place`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)

        currentTime = CHECKPOINT_INTERVAL
        advanceTimeBy(CHECKPOINT_INTERVAL + 1)
        runCurrent()

        assertEquals(1, fakeDao.sessions.size)
        assertEquals(CHECKPOINT_INTERVAL, fakeDao.sessions.single().activeMillis)

        currentTime = CHECKPOINT_INTERVAL * 2
        advanceTimeBy(CHECKPOINT_INTERVAL)
        runCurrent()

        // Same row, updated in place, not a second insert
        assertEquals(1, fakeDao.sessions.size)
        assertEquals(1, fakeDao.updateCount)
        assertEquals(CHECKPOINT_INTERVAL * 2, fakeDao.sessions.single().activeMillis)

        tracker.stop()
        runCurrent()
        assertEquals(1, fakeDao.sessions.size)
    }

    @Test
    fun `interaction after stop restarts a session for the same series`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)
        currentTime = ONE_MINUTE
        tracker.stop()
        runCurrent()
        assertFalse(tracker.isTracking)

        // Reader resumed without a content reload: a scroll must revive tracking
        currentTime = ONE_MINUTE * 2
        tracker.onInteraction()
        assertTrue(tracker.isTracking)

        currentTime = ONE_MINUTE * 3
        tracker.stop()
        runCurrent()

        assertEquals(2, fakeDao.sessions.size)
        assertEquals(NOVEL_1, fakeDao.sessions[1].novelKey)
        assertEquals(ONE_MINUTE, fakeDao.sessions[1].activeMillis)
        assertEquals(ONE_MINUTE * 2, fakeDao.sessions[1].startedAt)
    }

    @Test
    fun `chapter completions count once per url and emit events`() = runTest {
        val tracker = buildTracker()
        val events = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.completionEvents.collect { events.add(it) }
        }

        tracker.start(NOVEL_1)
        tracker.onChapterCompleted(CHAPTER_1)
        tracker.onChapterCompleted(CHAPTER_1) // duplicate, ignored
        tracker.onChapterCompleted(CHAPTER_2)
        tracker.onChapterCompleted(null) // no url, counted

        currentTime = ONE_MINUTE
        tracker.stop()
        runCurrent()

        assertEquals(listOf(1, 2, 3), events)
        assertEquals(3, fakeDao.sessions.single().chaptersCompleted)
        job.cancel()
    }

    @Test
    fun `tracking is inert when gamification is disabled`() = runTest {
        val tracker = buildTracker(enabled = false)
        tracker.start(NOVEL_1)
        assertFalse(tracker.isTracking)

        currentTime = ONE_MINUTE
        tracker.onInteraction()
        assertFalse(tracker.isTracking)
        tracker.stop()
        runCurrent()

        assertTrue(fakeDao.sessions.isEmpty())
    }

    @Test
    fun `sessions below the persistence floor are dropped`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)
        currentTime = BELOW_FLOOR
        tracker.stop()
        runCurrent()

        assertTrue(fakeDao.sessions.isEmpty())
    }

    @Test
    fun `switching series flushes the previous session`() = runTest {
        val tracker = buildTracker()
        tracker.start(NOVEL_1)
        currentTime = ONE_MINUTE
        tracker.start(NOVEL_2)
        runCurrent()

        assertEquals(1, fakeDao.sessions.size)
        assertEquals(NOVEL_1, fakeDao.sessions.single().novelKey)
        assertEquals(ONE_MINUTE, fakeDao.sessions.single().activeMillis)

        currentTime = ONE_MINUTE * 2
        tracker.stop()
        runCurrent()
        assertEquals(NOVEL_2, fakeDao.sessions[1].novelKey)
    }

    private class FakeReadingSessionDao : ReadingSessionDao {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var updateCount = 0

        override suspend fun insert(session: ReadingSessionEntity): Long {
            sessions.add(session.copy(id = sessions.size + 1L))
            return sessions.size.toLong()
        }

        override suspend fun insertAll(sessions: List<ReadingSessionEntity>) {
            sessions.forEach { insert(it) }
        }

        override suspend fun getAllSessions(): List<ReadingSessionEntity> = sessions

        override fun observeTotals(): Flow<SessionTotals> = flowOf(
            SessionTotals(
                totalActiveMillis = sessions.sumOf { it.activeMillis },
                totalChaptersCompleted = sessions.sumOf { it.chaptersCompleted },
                sessionCount = sessions.size
            )
        )

        override suspend fun getDistinctReadingDayCount(): Int =
            sessions.map { it.startedAt / MILLIS_PER_DAY }.distinct().size

        override suspend fun hasAnySessions(): Boolean = sessions.isNotEmpty()

        override suspend fun updateSessionProgress(
            id: Long,
            endedAt: Long,
            activeMillis: Long,
            chaptersCompleted: Int
        ) {
            updateCount++
            val index = sessions.indexOfFirst { it.id == id }
            if (index >= 0) {
                sessions[index] = sessions[index].copy(
                    endedAt = endedAt,
                    activeMillis = activeMillis,
                    chaptersCompleted = chaptersCompleted
                )
            }
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val NOVEL_1 = "Novel 1"
        private const val NOVEL_2 = "Novel 2"
        private const val CHAPTER_1 = "https://example.com/ch1"
        private const val CHAPTER_2 = "https://example.com/ch2"

        private const val ONE_MINUTE = 60_000L
        private const val FOUR_MINUTES = 240_000L
        private const val TWENTY_MINUTES = 1_200_000L
        private const val IDLE_WINDOW = 300_000L
        private const val CHECKPOINT_INTERVAL = 30_000L
        private const val BELOW_FLOOR = 5_000L
    }
}
