package io.aatricks.easyreader.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingSessionDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ReadingSessionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.readingSessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyTable_returnsZeros() = runBlocking {
        assertFalse(dao.hasAnySessions())
        val totals = dao.observeTotals().first()
        assertEquals(0L, totals.totalActiveMillis)
        assertEquals(0, totals.totalChaptersCompleted)
        assertEquals(0, totals.sessionCount)
        assertEquals(0, dao.getDistinctReadingDayCount())
        assertTrue(dao.getAllSessions().isEmpty())
    }

    @Test
    fun insertAndObserveTotalsAndDistinctDays() = runBlocking {
        val day1Millis = DAY_1_TIMESTAMP
        val day2Millis = DAY_2_TIMESTAMP

        val session1 = ReadingSessionEntity(
            novelKey = "Novel A",
            startedAt = day1Millis + SESSION_1_OFFSET,
            endedAt = day1Millis + SESSION_1_OFFSET + ACTIVE_TIME_1,
            activeMillis = ACTIVE_TIME_1,
            chaptersCompleted = CHAPTERS_1,
            seeded = false
        )
        val session2 = ReadingSessionEntity(
            novelKey = "Novel A",
            startedAt = day1Millis + SESSION_2_OFFSET,
            endedAt = day1Millis + SESSION_2_OFFSET + ACTIVE_TIME_2,
            activeMillis = ACTIVE_TIME_2,
            chaptersCompleted = CHAPTERS_2,
            seeded = false
        )
        val session3 = ReadingSessionEntity(
            novelKey = "Novel B",
            startedAt = day2Millis + SESSION_3_OFFSET,
            endedAt = day2Millis + SESSION_3_OFFSET + ACTIVE_TIME_3,
            activeMillis = ACTIVE_TIME_3,
            chaptersCompleted = CHAPTERS_3,
            seeded = true
        )

        dao.insert(session1)
        dao.insertAll(listOf(session2, session3))

        assertTrue(dao.hasAnySessions())
        val sessions = dao.getAllSessions()
        assertEquals(3, sessions.size)
        assertEquals("Novel A", sessions[0].novelKey)

        val totals = dao.observeTotals().first()
        assertEquals(ACTIVE_TIME_1 + ACTIVE_TIME_2 + ACTIVE_TIME_3, totals.totalActiveMillis)
        assertEquals(CHAPTERS_1 + CHAPTERS_2 + CHAPTERS_3, totals.totalChaptersCompleted)
        assertEquals(3, totals.sessionCount)

        assertEquals(2, dao.getDistinctReadingDayCount())
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DAY_1_TIMESTAMP = 100 * MILLIS_PER_DAY
        private const val DAY_2_TIMESTAMP = 101 * MILLIS_PER_DAY
        private const val SESSION_1_OFFSET = 1000L
        private const val SESSION_2_OFFSET = 5000L
        private const val SESSION_3_OFFSET = 2000L
        private const val ACTIVE_TIME_1 = 600_000L
        private const val ACTIVE_TIME_2 = 300_000L
        private const val ACTIVE_TIME_3 = 1_200_000L
        private const val CHAPTERS_1 = 2
        private const val CHAPTERS_2 = 1
        private const val CHAPTERS_3 = 4
    }
}
