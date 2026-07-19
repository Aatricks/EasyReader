package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.local.SessionTotals
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ScrollProgressionRepositoryTest {

    @Test
    fun `finished-series monotonicity`() = runTest {
        val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
        val totalsFlow = MutableStateFlow(SessionTotals(0, 0, 0))
        
        val dao = mock<ReadingSessionDao> {
            on { observeTotals() } doReturn totalsFlow
            onBlocking { getDistinctReadingDayCount() } doReturn 0
            onBlocking { getAllSessions() } doReturn emptyList()
        }
        
        val libraryRepo = mock<LibraryRepository> {
            on { libraryItems } doReturn itemsFlow
        }
        
        var finishedSeriesSet = emptySet<String>()
        val prefs = mock<PreferencesManager> {
            on { scrollFinishedSeries } doAnswer { finishedSeriesSet }
            on { scrollFinishedSeries = any() } doAnswer { finishedSeriesSet = it.getArgument(0) }
            on { scrollUnlockedMilestones } doReturn emptyMap()
            on { scrollUnlockedMilestones = any() } doAnswer { }
        }
        
        val repo = ScrollProgressionRepository(dao, libraryRepo, prefs) { 0L }
        
        // Initial -> 0 finished
        var prog = repo.progression.first()
        assertEquals(0, prog.finishedSeriesCount)
        
        // Add a finished series
        val finishedItem = LibraryItem(
            id = "1", title = "Series 1", url = "U", contentType = ContentType.WEB,
            dateAdded = 0L, lastRead = 0L, currentChapter = "1", baseTitle = "Series 1", progress = 100
        )
        itemsFlow.value = listOf(finishedItem)
        
        prog = repo.progression.first { it.finishedSeriesCount == 1 }
        assertEquals(1, prog.finishedSeriesCount)
        assertEquals(setOf("WEB::Series 1"), finishedSeriesSet)
        
        // Remove it from library
        itemsFlow.value = emptyList()
        
        // Progression still sees 1 finished series
        prog = repo.progression.first { it.finishedSeriesCount == 1 }
        assertEquals(1, prog.finishedSeriesCount)
        assertEquals(setOf("WEB::Series 1"), finishedSeriesSet)
    }

    @Test
    fun `milestone permanence`() = runTest {
        val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
        // Start with 10 chapters to unlock milestone
        val totalsFlow = MutableStateFlow(SessionTotals(0, 100, 0))
        
        val dao = mock<ReadingSessionDao> {
            on { observeTotals() } doReturn totalsFlow
            onBlocking { getDistinctReadingDayCount() } doReturn 0
            onBlocking { getAllSessions() } doReturn emptyList()
        }
        
        val libraryRepo = mock<LibraryRepository> {
            on { libraryItems } doReturn itemsFlow
        }
        
        var unlockedMap = emptyMap<String, Long>()
        val prefs = mock<PreferencesManager> {
            on { scrollFinishedSeries } doReturn emptySet()
            on { scrollFinishedSeries = any() } doAnswer { }
            on { scrollUnlockedMilestones } doAnswer { unlockedMap }
            on { scrollUnlockedMilestones = any() } doAnswer { unlockedMap = it.getArgument(0) }
        }
        
        val repo = ScrollProgressionRepository(dao, libraryRepo, prefs) { 1000L }
        
        // Milestone unlocked
        val prog1 = repo.progression.first { it.milestones.any { m -> m.id == "chapters_100" && m.unlockedAtMs != null } }
        val unlockedTime = prog1.milestones.first { it.id == "chapters_100" }.unlockedAtMs
        assertEquals(1000L, unlockedTime)
        
        // Now simulate user clearing app data but prefs intact (so totals drop to 0)
        // Wait, totalsFlow changes
        totalsFlow.value = SessionTotals(0, 0, 0)
        
        // It should still be unlocked because of the persisted map
        val prog2 = repo.progression.first { it.totalChaptersCompleted == 0 }
        val stillUnlockedTime = prog2.milestones.first { it.id == "chapters_100" }.unlockedAtMs
        assertEquals(1000L, stillUnlockedTime) // original timestamp kept
    }
    
    @Test
    fun `markAllMilestonesSeen updates seen count`() = runTest {
        val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
        val totalsFlow = MutableStateFlow(SessionTotals(0, 100, 0))
        
        val dao = mock<ReadingSessionDao> {
            on { observeTotals() } doReturn totalsFlow
            onBlocking { getDistinctReadingDayCount() } doReturn 0
            onBlocking { getAllSessions() } doReturn emptyList()
        }
        
        val libraryRepo = mock<LibraryRepository> {
            on { libraryItems } doReturn itemsFlow
        }
        
        var unlockedMap = mapOf("chapters_100" to 1000L)
        var seenSet = emptySet<String>()
        val prefs = mock<PreferencesManager> {
            on { scrollFinishedSeries } doReturn emptySet()
            on { scrollFinishedSeries = any() } doAnswer { }
            on { scrollUnlockedMilestones } doAnswer { unlockedMap }
            on { scrollUnlockedMilestones = any() } doAnswer { unlockedMap = it.getArgument(0) }
            on { scrollSeenMilestones } doAnswer { seenSet }
            on { scrollSeenMilestones = any() } doAnswer { seenSet = it.getArgument(0) }
        }
        
        val repo = ScrollProgressionRepository(dao, libraryRepo, prefs) { 1000L }
        
        // Wait for flow to emit non-empty progression
        repo.progression.first { it.milestones.isNotEmpty() }
        
        // Initially 2 unseen milestones (chapters_100 from mock, first_chapter unlocked live)
        val unseenCount = repo.unseenMilestoneCount.first { it > 0 }
        assertEquals(2, unseenCount)
        
        // Mark all seen
        repo.markAllMilestonesSeen()
        
        // Now unseen count is 0
        assertEquals(0, repo.unseenMilestoneCount.first())
        assertTrue("chapters_100" in seenSet)
        assertTrue("first_chapter" in seenSet)
    }

}
