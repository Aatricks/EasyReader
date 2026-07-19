package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.local.SessionTotals
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingSessionTrackerTest {

    private lateinit var fakeDao: FakeReadingSessionDao
    private var currentTime: Long = 0L
    private lateinit var tracker: ReadingSessionTracker

    @Before
    fun setup() {
        fakeDao = FakeReadingSessionDao()
        currentTime = 0L
        tracker = ReadingSessionTracker(
            readingSessionDao = fakeDao,
            clock = { currentTime },
            persistScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        )
    }

    @Test
    fun testActiveTimeAccumulation() = runTest {
        currentTime = TIME_START
        tracker.start(NOVEL_1)

        currentTime = TIME_INTERACTION_1
        tracker.onInteraction()

        currentTime = TIME_STOP_1
        tracker.stop()

        val sessions = fakeDao.insertedSessions
        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(NOVEL_1, session.novelKey)
        assertEquals(EXPECTED_ACTIVE_ACCUMULATED, session.activeMillis)
        assertEquals(TIME_START, session.startedAt)
        assertEquals(TIME_STOP_1, session.endedAt)
    }

    @Test
    fun testIdleGapExclusion() = runTest {
        currentTime = 0L
        tracker.start(NOVEL_1)

        currentTime = LARGE_GAP_TIME
        tracker.onInteraction()

        currentTime = LARGE_GAP_TIME
        tracker.stop()

        val sessions = fakeDao.insertedSessions
        assertEquals(1, sessions.size)
        assertEquals(MAX_IDLE_GAP, sessions.first().activeMillis)
    }

    @Test
    fun testChapterCompletionCounting() = runTest {
        currentTime = 0L
        tracker.start(NOVEL_1)

        tracker.onChapterCompleted(CHAPTER_1)
        tracker.onChapterCompleted(CHAPTER_1)
        tracker.onChapterCompleted(CHAPTER_2)

        currentTime = VALID_ACTIVE_TIME
        tracker.stop()

        val sessions = fakeDao.insertedSessions
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.first().chaptersCompleted)
    }

    @Test
    fun testFlushThresholdBelow10sNotPersisted() = runTest {
        currentTime = 0L
        tracker.start(NOVEL_1)

        currentTime = BELOW_THRESHOLD_TIME
        tracker.onInteraction()
        tracker.stop()

        assertTrue(fakeDao.insertedSessions.isEmpty())
    }

    @Test
    fun testNovelSwitchFlushes() = runTest {
        currentTime = 0L
        tracker.start(NOVEL_1)

        currentTime = VALID_ACTIVE_TIME
        tracker.onInteraction()

        currentTime = SWITCH_TIME
        tracker.start(NOVEL_2)

        assertEquals(1, fakeDao.insertedSessions.size)
        assertEquals(NOVEL_1, fakeDao.insertedSessions.first().novelKey)
        assertEquals(SWITCH_TIME, fakeDao.insertedSessions.first().activeMillis)

        currentTime = SWITCH_TIME + VALID_ACTIVE_TIME
        tracker.onInteraction()
        tracker.stop()

        assertEquals(2, fakeDao.insertedSessions.size)
        assertEquals(NOVEL_2, fakeDao.insertedSessions[1].novelKey)
    }

    private class FakeReadingSessionDao : ReadingSessionDao {
        val insertedSessions = mutableListOf<ReadingSessionEntity>()

        override suspend fun insert(session: ReadingSessionEntity): Long {
            insertedSessions.add(session)
            return insertedSessions.size.toLong()
        }

        override suspend fun insertAll(sessions: List<ReadingSessionEntity>) {
            insertedSessions.addAll(sessions)
        }

        override suspend fun getAllSessions(): List<ReadingSessionEntity> = insertedSessions

        override fun observeTotals(): Flow<SessionTotals> = flowOf(
            SessionTotals(
                totalActiveMillis = insertedSessions.sumOf { it.activeMillis },
                totalChaptersCompleted = insertedSessions.sumOf { it.chaptersCompleted },
                sessionCount = insertedSessions.size
            )
        )

        override suspend fun getDistinctReadingDayCount(): Int =
            insertedSessions.map { it.startedAt / MILLIS_PER_DAY }.distinct().size

        override suspend fun hasAnySessions(): Boolean = insertedSessions.isNotEmpty()
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val NOVEL_1 = "Novel 1"
        private const val NOVEL_2 = "Novel 2"
        private const val CHAPTER_1 = "https://example.com/ch1"
        private const val CHAPTER_2 = "https://example.com/ch2"

        private const val TIME_START = 1_000L
        private const val TIME_INTERACTION_1 = 10_000L
        private const val TIME_STOP_1 = 20_000L
        private const val EXPECTED_ACTIVE_ACCUMULATED = 19_000L

        private const val LARGE_GAP_TIME = 200_000L
        private const val MAX_IDLE_GAP = 150_000L

        private const val BELOW_THRESHOLD_TIME = 5_000L
        private const val VALID_ACTIVE_TIME = 15_000L
        private const val SWITCH_TIME = 20_000L
    }
}
