package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.SearchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val exploreRepository: ExploreRepository = mock()
    private lateinit var viewModel: ExploreViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        runBlocking {
            whenever(exploreRepository.getSourceNames()).thenReturn(listOf("NovelFire", "MangaBat"))
            whenever(exploreRepository.getTags(any())).thenReturn(emptyList())
            whenever(exploreRepository.getPopularNovels(any(), any(), any())).thenReturn(emptyList())
            whenever(exploreRepository.searchNovels(any(), any(), any())).thenReturn(emptyList())
            whenever(exploreRepository.searchNovelsDetailed(any(), any(), any()))
                .thenReturn(SearchOutcome(emptyList(), emptyList()))
            whenever(exploreRepository.getNovelDetails(any(), any())).thenReturn(null)
        }

        viewModel = ExploreViewModel(exploreRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `clearFilters resets search flow so the same query can run again`() = runTest {
        advanceUntilIdle()

        viewModel.updateSearchQuery("shadow")
        advanceTimeBy(500)
        advanceUntilIdle()
        verify(exploreRepository, times(1)).searchNovelsDetailed(eq("shadow"), eq(1), isNull())

        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.searchQuery)

        viewModel.updateSearchQuery("shadow")
        advanceTimeBy(500)
        advanceUntilIdle()

        verify(exploreRepository, times(2)).searchNovelsDetailed(eq("shadow"), eq(1), isNull())
        assertEquals("shadow", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `selectSource preserves search query and resets source scoped state`() = runTest {
        advanceUntilIdle()

        viewModel.updateSearchQuery("shadow")
        advanceTimeBy(500)
        advanceUntilIdle()

        viewModel.selectSource("NovelFire")
        advanceUntilIdle()

        assertEquals("shadow", viewModel.uiState.value.searchQuery)
        assertEquals("NovelFire", viewModel.uiState.value.selectedSource)
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
        verify(exploreRepository, times(1)).searchNovelsDetailed(eq("shadow"), eq(1), eq("NovelFire"))
    }

    @Test
    fun `clearFilters resets selection state`() = runTest {
        whenever(exploreRepository.getNovelDetails(any(), any())).thenReturn(
            ExploreItem(title = "Title", url = "url", source = "NovelFire")
        )

        advanceUntilIdle()
        viewModel.selectSource("NovelFire")
        advanceUntilIdle()
        viewModel.toggleTag("Action")
        advanceUntilIdle()
        viewModel.selectItem(ExploreItem(title = "Title", url = "url", source = "NovelFire"))
        advanceUntilIdle()

        viewModel.clearFilters()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.selectedSource)
        assertEquals(emptySet<String>(), state.selectedTags)
        assertEquals(1, state.page)
        assertNull(state.selectedItem)
        assertNull(state.selectedItemDetails)
        assertFalse(state.isSearching)
    }
}
