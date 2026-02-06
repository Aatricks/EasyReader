package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val testDispatcher = StandardTestDispatcher()

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
}
