package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.ReadingMode
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

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
        whenever(preferencesManager.readerTheme).thenReturn(ReaderTheme.DARK.name)
        whenever(preferencesManager.margins).thenReturn(16)
        whenever(preferencesManager.paragraphSpacing).thenReturn(1.0f)

        runTest {
            whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
            whenever(libraryRepository.getCurrentlyReading()).thenReturn(null)
            whenever(libraryRepository.markAsCurrentlyReading(any())).thenReturn(true)
            whenever(libraryRepository.updateProgress(any(), any(), any(), any(), any(), any(), any())).thenReturn(true)
            whenever(libraryRepository.updateReadingMode(any(), any())).thenReturn(true)
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
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(18f, state.fontSize)
        assertEquals(ReaderTheme.DARK, state.readerTheme)
    }

    @Test
    fun `loadContent saves current progress before loading new`() = runTest {
        // Setup initial item
        val initialItemId = "item-1"
        val initialUrl = "https://example.com/1"

        // Mock success for first load
        val result1 = ContentResult.Success(
            elements = emptyList(),
            title = "Title 1",
            url = initialUrl
        )
        whenever(contentRepository.loadContent(initialUrl)).thenReturn(result1)
        whenever(libraryRepository.getItemByUrl(initialUrl)).thenReturn(
            LibraryItem(id = initialItemId, title = "Title 1", url = initialUrl)
        )
        whenever(libraryRepository.getItemById(initialItemId)).thenReturn(
            LibraryItem(id = initialItemId, title = "Title 1", url = initialUrl)
        )

        viewModel.loadContent(initialUrl)
        advanceUntilIdle()

        assertEquals(initialUrl, viewModel.uiState.value.content?.url)

        // Now load a second item
        val nextUrl = "https://example.com/2"
        whenever(contentRepository.loadContent(nextUrl)).thenReturn(
            ContentResult.Success(emptyList(), "Title 2", nextUrl)
        )

        viewModel.loadContent(nextUrl)
        advanceUntilIdle()

        // Verify updateProgress was called for the INITIAL item
        verify(libraryRepository).updateProgress(
            itemId = eq(initialItemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(initialUrl),
            lastScrollProgress = any(),
            lastReadIndex = any(),
            lastReadOffset = any()
        )
    }

    @Test
    fun `updateScrollPosition saves progress after delay`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/1"

        // Set up current item
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Test")), "Test", url)
        )
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()

        viewModel.onUserInteraction()

        // Update scroll
        viewModel.updateScrollPosition(50f, 100f, 10f, 5, 10)

        // Should NOT have saved yet (debounced)
        verify(libraryRepository, never()).saveProgress(any(), any(), any(), any(), any(), any(), any())

        // Advance time
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()

        // Now it should have saved
        verify(libraryRepository).saveProgress(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(url),
            lastScrollProgress = any(),
            lastReadIndex = eq(5),
            lastReadOffset = eq(10)
        )
    }

    @Test
    fun `toggleReadingMode updates repository`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/1"

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(emptyList(), "Test", url)
        )
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()

        val initialPagedMode = viewModel.uiState.value.isPagedMode
        viewModel.toggleReadingMode()
        advanceUntilIdle()

        assertNotEquals(initialPagedMode, viewModel.uiState.value.isPagedMode)
        verify(libraryRepository).updateReadingMode(eq(itemId), any())
    }
}
