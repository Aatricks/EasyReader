package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.LibraryItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.system.measureTimeMillis

class LibraryRepositoryBenchmarkTest {

    @Mock
    private lateinit var preferencesManager: PreferencesManager

    @Mock
    private lateinit var libraryDao: LibraryDao

    @Mock
    private lateinit var exploreRepository: ExploreRepository

    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(preferencesManager.loadLibraryItems()).thenReturn(emptyList())
        whenever(libraryDao.getAllItems()).thenReturn(flowOf(emptyList()))
        repository = LibraryRepository(libraryDao, preferencesManager)
    }

    @Test
    fun benchmarkRefreshLibraryUpdates() = runBlocking {
        // Setup 100 novels (groups).
        // 50 "recent" (lastRead within 2 days)
        // 50 "old" (lastRead older than 10 days)

        val recentCutoff = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        val oldCutoff = System.currentTimeMillis() - 10 * 24 * 60 * 60 * 1000L

        val recentItems = (1..50).map { i ->
            LibraryItem(
                id = "recent_$i",
                title = "Recent Novel $i",
                url = "url_recent_$i",
                baseTitle = "Recent Novel $i",
                baseNovelUrl = "novel_recent_$i",
                sourceName = "Source1",
                totalChapters = 10,
                lastRead = recentCutoff + 10000L, // Definitely recent
                dateAdded = recentCutoff // Added recently
            )
        }

        val oldItems = (1..50).map { i ->
            LibraryItem(
                id = "old_$i",
                title = "Old Novel $i",
                url = "url_old_$i",
                baseTitle = "Old Novel $i",
                baseNovelUrl = "novel_old_$i",
                sourceName = "Source1",
                totalChapters = 10,
                lastRead = oldCutoff - 10000L, // Definitely old
                dateAdded = oldCutoff - 10000L // Added long ago
            )
        }

        val allItems = recentItems + oldItems

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(allItems))

        // Mock getNovelDetails with a delay to simulate network latency
        whenever(exploreRepository.getNovelDetails(any(), any())).thenAnswer {
            Thread.sleep(10) // Simulate 10ms network delay per request
            ExploreItem("Dummy", "url", source = "Source1", chapters = emptyList())
        }

        // Mock insertItems so it doesn't fail
        whenever(libraryDao.insertItems(any())).thenReturn(Unit)

        val time = measureTimeMillis {
            repository.refreshLibraryUpdates(exploreRepository)
        }

        println("BENCHMARK_RESULT: ${time}ms for 100 novels (50 recent, 50 old)")

        assertTrue(time > 0)
    }
}
