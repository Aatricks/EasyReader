package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.LibraryItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class LibraryRepositoryUpdateTest {

    private fun chapterList(size: Int, prefix: String): List<ChapterInfo> =
        List(size) { index -> ChapterInfo("Ch $index", "$prefix/ch-$index") }

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

        // Clear invocations from initialization
        clearInvocations(libraryDao)
    }

    @Test
    fun testUpdateNovelInfo_CallsDaoUpdate() = runBlocking {
        val itemId = "test-id"
        // Mock the updateNovelInfo call to return 1 (success)
        whenever(libraryDao.updateNovelInfo(any(), any(), any())).thenReturn(1)

        val result = repository.updateNovelInfo(itemId, "new-url", "new-source")

        assertTrue(result)

        // Verify that getAllItems was NOT called
        verify(libraryDao, never()).getAllItems()
        verify(libraryDao, never()).getItemById(any())
        verify(libraryDao, never()).insertItems(any())

        // Verify updateNovelInfo was called with correct params
        verify(libraryDao).updateNovelInfo(itemId, "new-url", "new-source")
        Unit
    }

    @Test
    fun testUpdateNovelInfo_ReturnsFalseOnFailure() = runBlocking {
        val itemId = "test-id"
        // Mock the updateNovelInfo call to return 0 (failure/no rows updated)
        whenever(libraryDao.updateNovelInfo(any(), any(), any())).thenReturn(0)

        val result = repository.updateNovelInfo(itemId, "new-url", "new-source")

        assertEquals(false, result)

        verify(libraryDao).updateNovelInfo(itemId, "new-url", "new-source")
        Unit
    }

    @Test
    fun testRefreshLibraryUpdates_batches_updates() = runBlocking {
        // Setup 3 novels with updates
        val item1 = LibraryItem(
            id = "1", title = "Novel 1", url = "url1",
            baseTitle = "Novel 1", baseNovelUrl = "novel1", sourceName = "Source1",
            totalChapters = 10
        )
        val item2 = LibraryItem(
            id = "2", title = "Novel 2", url = "url2",
            baseTitle = "Novel 2", baseNovelUrl = "novel2", sourceName = "Source1",
            totalChapters = 20
        )
        val item3 = LibraryItem(
            id = "3", title = "Novel 3", url = "url3",
            baseTitle = "Novel 3", baseNovelUrl = "novel3", sourceName = "Source1",
            totalChapters = 30
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(item1, item2, item3)))

        // Mock explore repo to return more chapters for each
        val chapters1 = chapterList(15, "novel1")
        val chapters2 = chapterList(25, "novel2")
        val chapters3 = chapterList(35, "novel3")

        whenever(exploreRepository.getNovelDetails("novel1", "Source1"))
            .thenReturn(ExploreItem("Novel 1", "novel1", source = "Source1", chapters = chapters1))
        whenever(exploreRepository.getNovelDetails("novel2", "Source1"))
            .thenReturn(ExploreItem("Novel 2", "novel2", source = "Source1", chapters = chapters2))
        whenever(exploreRepository.getNovelDetails("novel3", "Source1"))
            .thenReturn(ExploreItem("Novel 3", "novel3", source = "Source1", chapters = chapters3))

        // Execute
        repository.refreshLibraryUpdates(exploreRepository)

        // Verify behavior: Optimized behavior calls insertItems once with all updates.
        verify(libraryDao, times(1)).insertItems(any())
    }

    @Test
    fun testRefreshLibraryUpdates_partial_failure() = runBlocking {
        // Setup 2 novels, one will fail
        val item1 = LibraryItem(id = "1", title = "Novel 1", url = "url1", baseTitle = "Novel 1", baseNovelUrl = "novel1", sourceName = "Source1", totalChapters = 10)
        val item2 = LibraryItem(id = "2", title = "Novel 2", url = "url2", baseTitle = "Novel 2", baseNovelUrl = "novel2", sourceName = "Source1", totalChapters = 20)

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(item1, item2)))

        // Mock explore repo: novel1 succeeds, novel2 fails
        val chapters1 = chapterList(15, "novel1")
        whenever(exploreRepository.getNovelDetails("novel1", "Source1")).thenReturn(ExploreItem("Novel 1", "novel1", source = "Source1", chapters = chapters1))
        whenever(exploreRepository.getNovelDetails("novel2", "Source1")).thenThrow(RuntimeException("Network error"))

        // Execute
        repository.refreshLibraryUpdates(exploreRepository)

        // Verify behavior: It should still update the successful one
        verify(libraryDao, times(1)).insertItems(argThat { 
            size == 1 && first().id == "1" && first().totalChapters == 15 
        })
    }

    @Test
    fun testRefreshLibraryUpdates_skips_old_novels() = runBlocking {
        // Setup 1 recent novel (updated recently) and 1 old novel (not updated/read recently)
        val recentCutoff = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        val oldCutoff = System.currentTimeMillis() - 10 * 24 * 60 * 60 * 1000L

        val recentItem = LibraryItem(
            id = "recent", title = "Recent Novel", url = "url_recent",
            baseTitle = "Recent Novel", baseNovelUrl = "novel_recent", sourceName = "Source1",
            totalChapters = 10,
            lastRead = recentCutoff,
            dateAdded = recentCutoff
        )
        val oldItem = LibraryItem(
            id = "old", title = "Old Novel", url = "url_old",
            baseTitle = "Old Novel", baseNovelUrl = "novel_old", sourceName = "Source1",
            totalChapters = 10,
            lastRead = oldCutoff,
            dateAdded = oldCutoff
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(recentItem, oldItem)))

        // Mock explore repo
        val chapters = chapterList(15, "novel_recent")
        whenever(exploreRepository.getNovelDetails("novel_recent", "Source1"))
            .thenReturn(ExploreItem("Recent Novel", "novel_recent", source = "Source1", chapters = chapters))

        // Execute
        repository.refreshLibraryUpdates(exploreRepository)

        // Verify that only the recent novel was checked
        verify(exploreRepository).getNovelDetails("novel_recent", "Source1")
        verify(exploreRepository, never()).getNovelDetails("novel_old", "Source1")

        // Verify that only recent items were updated in DB
        verify(libraryDao).insertItems(argThat {
            size == 1 && first().id == "recent" && first().totalChapters == 15
        })
    }

    @Test
    fun testRefreshLibraryUpdates_includes_old_but_currently_reading_novels() = runBlocking {
        // Setup 1 old novel (lastRead > 7 days ago) but currently reading
        val oldCutoff = System.currentTimeMillis() - 10 * 24 * 60 * 60 * 1000L

        val oldButReadingItem = LibraryItem(
            id = "old_but_reading", title = "Old Novel Reading", url = "url_old_reading",
            baseTitle = "Old Novel Reading", baseNovelUrl = "novel_old_reading", sourceName = "Source1",
            totalChapters = 10,
            lastRead = oldCutoff,
            dateAdded = oldCutoff,
            isCurrentlyReading = true
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(oldButReadingItem)))

        // Mock explore repo
        val chapters = chapterList(15, "novel_old_reading")
        whenever(exploreRepository.getNovelDetails("novel_old_reading", "Source1"))
            .thenReturn(ExploreItem("Old Novel Reading", "novel_old_reading", source = "Source1", chapters = chapters))

        // Execute
        repository.refreshLibraryUpdates(exploreRepository)

        // Verify that the novel was checked
        verify(exploreRepository).getNovelDetails("novel_old_reading", "Source1")

        // Verify that it was updated
        verify(libraryDao).insertItems(argThat {
            size == 1 && first().id == "old_but_reading" && first().totalChapters == 15
        })
    }
}
