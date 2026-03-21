package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private lateinit var testDispatcher: TestDispatcher

    private val libraryDao: LibraryDao = mock {
        on { getAllItems() } doReturn flowOf(emptyList())
    }
    private val preferencesManager: PreferencesManager = mock {
        on { loadLibraryItems() } doReturn emptyList()
        on { loadCollapsedSources() } doReturn emptySet()
    }
    private val libraryRepository by lazy { LibraryRepository(libraryDao, preferencesManager) }

    private val contentRepository: ContentRepository = mock()
    private val exploreRepository: ExploreRepository = mock()

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setup() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        viewModel = LibraryViewModel(
            libraryRepository,
            contentRepository,
            exploreRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        // It should verify that state loads correctly and doesn't crash on ignoreSslErrors access (as it was removed)
        assertNotNull(state)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `toggle selection updates selection mode`() = runTest {
        val itemId = "id-1"
        advanceUntilIdle()

        viewModel.toggleSelection(itemId)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf(itemId), viewModel.uiState.value.selectedIds)

        viewModel.clearSelection()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `toggle source expansion updates collapsed sources and persists`() = runTest {
        val vm = LibraryViewModel(libraryRepository, contentRepository, exploreRepository)
        advanceUntilIdle()

        vm.toggleSourceExpansion("NovelFire")
        advanceUntilIdle()
        assertTrue("NovelFire" in vm.uiState.value.collapsedSources)

        vm.toggleSourceExpansion("NovelFire")
        advanceUntilIdle()
        assertFalse("NovelFire" in vm.uiState.value.collapsedSources)

        verify(preferencesManager, atLeastOnce()).saveCollapsedSources(any())
    }
}
