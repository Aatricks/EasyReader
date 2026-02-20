package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSecurityTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    lateinit var contentRepository: ContentRepository

    @Mock
    lateinit var libraryRepository: LibraryRepository

    @Mock
    lateinit var exploreRepository: ExploreRepository

    @Mock
    lateinit var preferencesManager: PreferencesManager

    private lateinit var viewModel: ReaderViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        whenever(preferencesManager.fontSize).thenReturn(18f)
        whenever(preferencesManager.lineHeight).thenReturn(1.5f)
        whenever(preferencesManager.fontFamily).thenReturn("Default")
        whenever(preferencesManager.readerTheme).thenReturn("DARK") // Use String directly as it's an enum name
        whenever(preferencesManager.margins).thenReturn(16)
        whenever(preferencesManager.paragraphSpacing).thenReturn(1.0f)

        runTest {
             whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
             whenever(libraryRepository.getCurrentlyReading()).thenReturn(null)
        }

        viewModel = ReaderViewModel(
            contentRepository,
            libraryRepository,
            exploreRepository,
            preferencesManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `requestOpenFile updates state correctly`() = runTest {
        val fileUri = "file:///sdcard/test.epub"

        viewModel.requestOpenFile(fileUri)

        val state = viewModel.uiState.value
        assertEquals(fileUri, state.pendingFileConfirmationUri)
        assertTrue(state.showFileConfirmationDialog)
    }

    @Test
    fun `dismissFileConfirmation clears state`() = runTest {
        val fileUri = "file:///sdcard/test.epub"
        viewModel.requestOpenFile(fileUri)

        var state = viewModel.uiState.value
        assertTrue(state.showFileConfirmationDialog)

        viewModel.dismissFileConfirmation()

        state = viewModel.uiState.value
        assertNull(state.pendingFileConfirmationUri)
        assertFalse(state.showFileConfirmationDialog)
    }
}
