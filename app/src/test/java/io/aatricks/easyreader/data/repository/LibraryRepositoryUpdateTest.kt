package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        whenever(preferencesManager.loadCollapsedSources()).thenReturn(emptySet())
        whenever(libraryDao.getAllItems()).thenReturn(flowOf(emptyList()))
        repository = LibraryRepository(libraryDao, preferencesManager)

        // Clear invocations from initialization
        clearInvocations(libraryDao)
    }

    @Test
    fun `healChapterMetadata writes only targeted meta columns and never a whole-row replace`() = runBlocking {
        val itemId = "heal-id"

        val result = repository.healChapterMetadata(
            itemId = itemId,
            totalChapters = 42,
            currentChapter = "Chapter 42",
            markHasUpdates = true
        )

        assertTrue(result)
        verify(libraryDao).updateTotalChapters(itemId, 42)
        verify(libraryDao).updateCurrentChapter(itemId, "Chapter 42")
        verify(libraryDao).markHasUpdates(itemId)
        // The whole point of the fix: no whole-row REPLACE, so a concurrent progress write can
        // never be clobbered. insertItem is the REPLACE funnel used by updateItem/progress writes.
        verify(libraryDao, never()).insertItem(any())
        verify(libraryDao, never()).getItemById(any())
        Unit
    }

    @Test
    fun `healChapterMetadata skips null fields and the unset updates flag`() = runBlocking {
        val itemId = "heal-id"

        repository.healChapterMetadata(
            itemId = itemId,
            totalChapters = null,
            currentChapter = "Chapter 7",
            markHasUpdates = false
        )

        verify(libraryDao, never()).updateTotalChapters(any(), any())
        verify(libraryDao).updateCurrentChapter(itemId, "Chapter 7")
        verify(libraryDao, never()).markHasUpdates(any())
        verify(libraryDao, never()).insertItem(any())
        Unit
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
            currentChapter = "Ch 10",
            totalChapters = 10,
            progress = 100
        )
        val item2 = LibraryItem(
            id = "2", title = "Novel 2", url = "url2",
            baseTitle = "Novel 2", baseNovelUrl = "novel2", sourceName = "Source1",
            currentChapter = "Ch 20",
            totalChapters = 20,
            progress = 100
        )
        val item3 = LibraryItem(
            id = "3", title = "Novel 3", url = "url3",
            baseTitle = "Novel 3", baseNovelUrl = "novel3", sourceName = "Source1",
            currentChapter = "Ch 30",
            totalChapters = 30,
            progress = 100
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

        // Verify behavior: Optimized behavior calls updateTotalChapters.
        verify(libraryDao).updateTotalChapters("1", 15)
        verify(libraryDao).updateTotalChapters("2", 25)
        verify(libraryDao).updateTotalChapters("3", 35)
        verify(libraryDao, never()).insertItems(any())
    }

    @Test
    fun testRefreshLibraryUpdates_does_not_mark_unfinished_latest_chapter_as_update() = runBlocking {
        val item = LibraryItem(
            id = "unfinished",
            title = "Novel Chapter 10",
            url = "novel/ch-10",
            currentChapter = "Ch 10",
            baseTitle = "Novel",
            baseNovelUrl = "novel",
            sourceName = "Source1",
            totalChapters = 10,
            progress = 50
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(item)))
        whenever(exploreRepository.getNovelDetails("novel", "Source1"))
            .thenReturn(ExploreItem("Novel", "novel", source = "Source1", chapters = chapterList(11, "novel")))

        repository.refreshLibraryUpdates(exploreRepository)

        verify(libraryDao).updateTotalChapters("unfinished", 11)
        verify(libraryDao, never()).markHasUpdates(any())
        verify(libraryDao, never()).insertItems(any())
    }

    @Test
    fun testRefreshLibraryUpdates_marks_update_when_latest_chapter_is_nearly_complete() = runBlocking {
        val item = LibraryItem(
            id = "caught-up",
            title = "Novel Chapter 10",
            url = "novel/ch-10",
            currentChapter = "Ch 10",
            baseTitle = "Novel",
            baseNovelUrl = "novel",
            sourceName = "Source1",
            totalChapters = 10,
            progress = 90
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(item)))
        whenever(exploreRepository.getNovelDetails("novel", "Source1"))
            .thenReturn(ExploreItem("Novel", "novel", source = "Source1", chapters = chapterList(11, "novel")))

        repository.refreshLibraryUpdates(exploreRepository)

        verify(libraryDao).updateTotalChapters("caught-up", 11)
        verify(libraryDao).markHasUpdates("caught-up")
        verify(libraryDao, never()).insertItems(any())
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
        verify(libraryDao).updateTotalChapters("1", 15)
        verify(libraryDao, never()).updateTotalChapters(eq("2"), any())
        verify(libraryDao, never()).insertItems(any())
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
        verify(libraryDao).updateTotalChapters("recent", 15)
        verify(libraryDao, never()).updateTotalChapters(eq("old"), any())
        verify(libraryDao, never()).insertItems(any())
    }

    @Test
    fun `refresh checks old finished novel after all downloads are deleted`() = runBlocking {
        val oldTimestamp = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
        val finishedItem = LibraryItem(
            id = "finished",
            title = "Finished Novel Chapter 10",
            url = "finished/ch-10",
            currentChapter = "Ch 10",
            baseTitle = "Finished Novel",
            baseNovelUrl = "finished",
            sourceName = "Source1",
            totalChapters = 10,
            progress = 100,
            lastRead = oldTimestamp,
            dateAdded = oldTimestamp,
            isDownloaded = false
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(finishedItem)))
        whenever(exploreRepository.getNovelDetails("finished", "Source1"))
            .thenReturn(
                ExploreItem(
                    "Finished Novel",
                    "finished",
                    source = "Source1",
                    chapters = chapterList(11, "finished")
                )
            )

        repository.refreshLibraryUpdates(exploreRepository)

        verify(exploreRepository).getNovelDetails("finished", "Source1")
        verify(libraryDao).updateTotalChapters("finished", 11)
        verify(libraryDao).markHasUpdates("finished")
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
        verify(libraryDao).updateTotalChapters("old_but_reading", 15)
        verify(libraryDao, never()).insertItems(any())
    }

    @Test
    fun testRefreshLibraryUpdates_checks_old_finished_novels_when_threshold_ignored() = runBlocking {
        // Simulates a just-imported, finished series: lastRead/dateAdded restored from an old
        // backup, so the periodic path would skip it. A post-import forced run must still check
        // it and surface the NEW pill (hasUpdates) when the source has new chapters.
        val oldCutoff = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L

        val importedFinished = LibraryItem(
            id = "imported", title = "Imported Novel Chapter 10", url = "novel_imported/ch-10",
            currentChapter = "Ch 10",
            baseTitle = "Imported Novel", baseNovelUrl = "novel_imported", sourceName = "Source1",
            totalChapters = 10,
            progress = 100,
            lastRead = oldCutoff,
            dateAdded = oldCutoff
        )

        whenever(libraryDao.getAllItems()).thenReturn(flowOf(listOf(importedFinished)))
        whenever(exploreRepository.getNovelDetails("novel_imported", "Source1"))
            .thenReturn(
                ExploreItem(
                    "Imported Novel", "novel_imported", source = "Source1",
                    chapters = chapterList(15, "novel_imported")
                )
            )

        repository.refreshLibraryUpdates(exploreRepository, ignoreActivityThreshold = true)

        verify(exploreRepository).getNovelDetails("novel_imported", "Source1")
        verify(libraryDao).updateTotalChapters("imported", 15)
        verify(libraryDao).markHasUpdates("imported")
        verify(libraryDao, never()).insertItems(any())
    }

    @Test
    fun `refresh preserves progress written during the refresh window`() = runBlocking {
        val item = LibraryItem(
            id = "1", title = "Novel 1", url = "url1",
            baseTitle = "Novel 1", baseNovelUrl = "novel1", sourceName = "Source1",
            currentChapter = "Ch 10",
            totalChapters = 10,
            progress = 50,
            lastReadElementKey = "oldKey"
        )
        val dbItems = mutableMapOf("1" to item)
        whenever(libraryDao.getAllItems()).thenAnswer { flowOf(dbItems.values.toList()) }
        whenever(libraryDao.getItemById("1")).thenAnswer { dbItems["1"] }
        whenever(libraryDao.insertItem(any())).thenAnswer { inv ->
            val itm = inv.arguments[0] as LibraryItem
            dbItems[itm.id] = itm
            Unit
        }
        whenever(libraryDao.updateTotalChapters(any(), any())).thenAnswer { inv ->
            val id = inv.arguments[0] as String
            val chapters = inv.arguments[1] as Int
            dbItems[id]?.let {
                dbItems[id] = it.copy(totalChapters = chapters)
            }
            Unit
        }

        // We want getNovelDetails to suspend, allowing us to update progress concurrently
        val deferredNovelDetails = kotlinx.coroutines.CompletableDeferred<ExploreItem>()
        whenever(exploreRepository.getNovelDetails("novel1", "Source1")).thenAnswer {
            runBlocking { deferredNovelDetails.await() }
        }

        val refreshJob = launch {
            repository.refreshLibraryUpdates(exploreRepository)
        }

        // yield to let refreshLibraryUpdates start and wait on getNovelDetails
        kotlinx.coroutines.yield()

        // updateProgressExplicit concurrently
        repository.updateProgressExplicit(
            itemId = "1",
            progress = FieldUpdate.Set(80),
            lastReadElementKey = FieldUpdate.Set("newKey")
        )

        // Now resume getNovelDetails with more chapters
        val chapters = chapterList(15, "novel1")
        deferredNovelDetails.complete(ExploreItem("Novel 1", "novel1", source = "Source1", chapters = chapters))

        refreshJob.join()

        val finalItem = dbItems["1"]!!
        assertEquals(80, finalItem.progress)
        assertEquals("newKey", finalItem.lastReadElementKey)
        assertEquals(15, finalItem.totalChapters)
    }

    @Test
    fun `refresh keeps per-source chapter counts independent`() = runBlocking {
        val item1 = LibraryItem(
            id = "1", title = "Novel", url = "url1",
            baseTitle = "Novel", baseNovelUrl = "novel_src1", sourceName = "Source1",
            totalChapters = 10
        )
        val item2 = LibraryItem(
            id = "2", title = "Novel", url = "url2",
            baseTitle = "Novel", baseNovelUrl = "novel_src2", sourceName = "Source2",
            totalChapters = 10
        )
        val dbItems = mutableMapOf("1" to item1, "2" to item2)
        whenever(libraryDao.getAllItems()).thenAnswer { flowOf(dbItems.values.toList()) }
        whenever(libraryDao.updateTotalChapters(any(), any())).thenAnswer { inv ->
            val id = inv.arguments[0] as String
            val chapters = inv.arguments[1] as Int
            dbItems[id]?.let {
                dbItems[id] = it.copy(totalChapters = chapters)
            }
            Unit
        }

        whenever(exploreRepository.getNovelDetails("novel_src1", "Source1"))
            .thenReturn(ExploreItem("Novel", "novel_src1", source = "Source1", chapters = chapterList(15, "novel_src1")))
        whenever(exploreRepository.getNovelDetails("novel_src2", "Source2"))
            .thenReturn(ExploreItem("Novel", "novel_src2", source = "Source2", chapters = chapterList(20, "novel_src2")))

        repository.refreshLibraryUpdates(exploreRepository)

        assertEquals(15, dbItems["1"]?.totalChapters)
        assertEquals(20, dbItems["2"]?.totalChapters)
    }

    @Test
    fun `refresh marks hasUpdates only on the caught-up marker item`() = runBlocking {
        // Group of 3 items under same baseTitle, same sourceName.
        // One is currently reading (item2), one is older (item1), one is newer but not read (item3).
        val item1 = LibraryItem(
            id = "1", title = "Novel Ch 5", url = "url1",
            baseTitle = "Novel", baseNovelUrl = "novel", sourceName = "Source1",
            currentChapter = "Ch 5", totalChapters = 10, progress = 100, hasUpdates = false
        )
        val item2 = LibraryItem(
            id = "2", title = "Novel Ch 10", url = "url10",
            baseTitle = "Novel", baseNovelUrl = "novel", sourceName = "Source1",
            currentChapter = "Ch 10", totalChapters = 10, progress = 90, hasUpdates = false,
            isCurrentlyReading = true
        )
        val item3 = LibraryItem(
            id = "3", title = "Novel Ch 10", url = "url3",
            baseTitle = "Novel", baseNovelUrl = "novel", sourceName = "Source1",
            currentChapter = "Ch 10", totalChapters = 10, progress = 0, hasUpdates = false
        )
        val dbItems = mutableMapOf("1" to item1, "2" to item2, "3" to item3)
        whenever(libraryDao.getAllItems()).thenAnswer { flowOf(dbItems.values.toList()) }
        whenever(libraryDao.updateTotalChapters(any(), any())).thenAnswer { inv ->
            val id = inv.arguments[0] as String
            val chapters = inv.arguments[1] as Int
            dbItems[id]?.let {
                dbItems[id] = it.copy(totalChapters = chapters)
            }
            Unit
        }
        whenever(libraryDao.markHasUpdates(any())).thenAnswer { inv ->
            val id = inv.arguments[0] as String
            dbItems[id]?.let {
                dbItems[id] = it.copy(hasUpdates = true)
            }
            Unit
        }

        whenever(exploreRepository.getNovelDetails("novel", "Source1"))
            .thenReturn(ExploreItem("Novel", "novel", source = "Source1", chapters = chapterList(12, "novel")))

        repository.refreshLibraryUpdates(exploreRepository)

        // item2 is currently reading and caught up (progress 90 >= 90% of totalChapters 10).
        // It should be marked with hasUpdates.
        // item1 and item3 should NOT be marked.
        assertTrue(dbItems["2"]!!.hasUpdates)
        assertFalse(dbItems["1"]!!.hasUpdates)
        assertFalse(dbItems["3"]!!.hasUpdates)

        assertEquals(12, dbItems["1"]!!.totalChapters)
        assertEquals(12, dbItems["2"]!!.totalChapters)
        assertEquals(12, dbItems["3"]!!.totalChapters)
    }
}
