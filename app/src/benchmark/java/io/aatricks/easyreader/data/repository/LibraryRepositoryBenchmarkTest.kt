package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.LibraryItem
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
        // Setup 10000 novels (groups).

        val recentCutoff = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L

        val recentItems = (1..10000).map { i ->
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

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(recentItems))

        // Mock getNovelDetails with NO delay to simulate pure overhead
        whenever(exploreRepository.getNovelDetails(any(), any())).thenAnswer {
            ExploreItem("Dummy", "url", source = "Source1", chapters = emptyList())
        }

        // Mock insertItems so it doesn't fail
        whenever(libraryDao.insertItems(any())).thenReturn(Unit)

        val time = measureTimeMillis {
            repository.refreshLibraryUpdates(exploreRepository)
        }

        println("BENCHMARK_RESULT: ${time}ms for 10000 novels")

        assertTrue(time > 0)
    }
}
