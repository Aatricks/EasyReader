package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.local.SessionTotals
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

class ReadingHistorySeederTest {

    @Mock
    private lateinit var libraryDao: LibraryDao

    @Mock
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var fakeSessionDao: FakeReadingSessionDao
    private lateinit var seeder: ReadingHistorySeeder

    private var seededFlag: Boolean = false

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        fakeSessionDao = FakeReadingSessionDao()
        seededFlag = false

        whenever(preferencesManager.scrollHistorySeeded).thenAnswer { seededFlag }
        doAnswer { invocation ->
            seededFlag = invocation.arguments[0] as Boolean
            Unit
        }.whenever(preferencesManager).scrollHistorySeeded = any()

        seeder = ReadingHistorySeeder(
            libraryDao = libraryDao,
            readingSessionDao = fakeSessionDao,
            preferencesManager = preferencesManager
        )
    }

    @Test
    fun testGroupsByNovelAndDayAndCapsAt4Hours() = runTest {
        val day1 = DAY_1_TIMESTAMP
        val day2 = DAY_2_TIMESTAMP

        // Day 1 Novel A: 60 chapters (5 min * 60 = 300 min = 5h -> capped at 4h = 14,400,000 ms), 50 completed >=90
        val novelAChaptersDay1 = List(60) { index ->
            LibraryItem(
                id = "a_day1_$index",
                title = "Novel A Ch $index",
                url = "https://example.com/a/day1/$index",
                baseTitle = "Novel A",
                lastRead = day1 + index * 1000L,
                progress = if (index < 50) 95 else 50
            )
        }

        // Day 1 Novel B: 2 chapters (5 min * 2 = 10 min = 600,000 ms), 1 completed >=90
        val novelBChaptersDay1 = List(2) { index ->
            LibraryItem(
                id = "b_day1_$index",
                title = "Novel B Ch $index",
                url = "https://example.com/b/day1/$index",
                baseTitle = "Novel B",
                lastRead = day1 + index * 1000L,
                progress = if (index < 1) 90 else 40
            )
        }

        // Day 2 Novel A: 3 chapters (5 min * 3 = 15 min = 900,000 ms), 2 completed >=90
        val novelAChaptersDay2 = List(3) { index ->
            LibraryItem(
                id = "a_day2_$index",
                title = "Novel A Ch2 $index",
                url = "https://example.com/a/day2/$index",
                baseTitle = "Novel A",
                lastRead = day2 + index * 1000L,
                progress = if (index < 2) 100 else 30
            )
        }

        val allItems = novelAChaptersDay1 + novelBChaptersDay1 + novelAChaptersDay2
        whenever(libraryDao.getAllItemsDirect()).thenReturn(allItems)

        seeder.seedIfNeeded()

        assertTrue(seededFlag)
        val sessions = fakeSessionDao.insertedSessions
        assertEquals(3, sessions.size)

        val novelADay1Session = sessions.first { it.novelKey == "Novel A" && it.startedAt == day1 }
        assertEquals(FOUR_HOURS_MILLIS, novelADay1Session.activeMillis)
        assertEquals(50, novelADay1Session.chaptersCompleted)
        assertTrue(novelADay1Session.seeded)

        val novelBDay1Session = sessions.first { it.novelKey == "Novel B" && it.startedAt == day1 }
        assertEquals(TEN_MINUTES_MILLIS, novelBDay1Session.activeMillis)
        assertEquals(1, novelBDay1Session.chaptersCompleted)
        assertTrue(novelBDay1Session.seeded)

        val novelADay2Session = sessions.first { it.novelKey == "Novel A" && it.startedAt == day2 }
        assertEquals(FIFTEEN_MINUTES_MILLIS, novelADay2Session.activeMillis)
        assertEquals(2, novelADay2Session.chaptersCompleted)
        assertTrue(novelADay2Session.seeded)
    }

    @Test
    fun testNoOpWhenSessionsExist() = runTest {
        val existingSession = ReadingSessionEntity(
            novelKey = "Novel X",
            startedAt = 1000L,
            endedAt = 2000L,
            activeMillis = 1000L,
            chaptersCompleted = 1,
            seeded = false
        )
        fakeSessionDao.insertedSessions.add(existingSession)

        seeder.seedIfNeeded()

        assertTrue(seededFlag)
        assertEquals(1, fakeSessionDao.insertedSessions.size)
        assertEquals("Novel X", fakeSessionDao.insertedSessions.first().novelKey)
    }

    @Test
    fun testNoOpWhenAlreadySeeded() = runTest {
        seededFlag = true

        seeder.seedIfNeeded()

        assertTrue(fakeSessionDao.insertedSessions.isEmpty())
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
        private const val DAY_1_TIMESTAMP = 100 * MILLIS_PER_DAY
        private const val DAY_2_TIMESTAMP = 101 * MILLIS_PER_DAY
        private const val FOUR_HOURS_MILLIS = 14_400_000L
        private const val TEN_MINUTES_MILLIS = 600_000L
        private const val FIFTEEN_MINUTES_MILLIS = 900_000L
    }
}
