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
    fun `loadContent uses saved pdf page as preload hint`() = runTest {
        val itemId = "pdf-item"
        val pdfUrl = "/tmp/sample.pdf"
        val savedPageIndex = 4
        val pdfItem = LibraryItem(
            id = itemId,
            title = "Sample PDF",
            url = pdfUrl,
            contentType = ContentType.PDF,
            progress = 55,
            lastReadIndex = savedPageIndex,
            lastReadOffset = 18,
            lastScrollPosition = 55f
        )
        val preloadedPages = List(savedPageIndex) { index ->
            ContentElement.Placeholder("Loading page ${index + 1}...")
        } + ContentElement.PageContent(listOf(ContentElement.Text("Saved PDF page")))

        whenever(contentRepository.loadContent(pdfUrl, savedPageIndex)).thenReturn(
            ContentResult.Success(preloadedPages, "Sample PDF", pdfUrl)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(pdfItem)

        viewModel.loadContent(pdfUrl, itemId)
        advanceUntilIdle()

        verify(contentRepository).loadContent(pdfUrl, savedPageIndex)
        assertEquals(savedPageIndex, viewModel.uiState.value.scrollIndex)
        assertTrue(viewModel.uiState.value.content?.paragraphs?.get(savedPageIndex) is ContentElement.PageContent)
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
    fun `updateScrollPosition ignores micro variations on same item`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/1"

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

        viewModel.updateScrollPosition(
            scrollOffset = 30f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 2,
            offset = 100
        )
        val stateAfterFirstUpdate = viewModel.uiState.value

        viewModel.updateScrollPosition(
            scrollOffset = 30.1f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 2,
            offset = 103
        )

        val stateAfterSecondUpdate = viewModel.uiState.value
        assertEquals(stateAfterFirstUpdate.scrollOffset, stateAfterSecondUpdate.scrollOffset)
        assertEquals(stateAfterFirstUpdate.scrollPosition, stateAfterSecondUpdate.scrollPosition, 0.001f)

        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()

        verify(libraryRepository, times(1)).saveProgress(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(url),
            lastScrollProgress = any(),
            lastReadIndex = eq(2),
            lastReadOffset = eq(100)
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

    @Test
    fun `updateReadingProgress ignores placeholder content`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/pdf"

        // Setup item with placeholder content
        val placeholderContent = listOf(ContentElement.Text("Loading page 5..."))
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(placeholderContent, "PDF", url)
        )
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(
            LibraryItem(id = itemId, title = "PDF", url = url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "PDF", url = url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()

        // Try to update progress while index 0 is a placeholder
        viewModel.updateReadingProgress(50, 50f, 0, 0)
        advanceUntilIdle()

        // Should NOT have called libraryRepository.saveProgress
        verify(libraryRepository, never()).saveProgress(any(), any(), any(), any(), any(), any(), any())

        // Now setup item with REAL content
        val realContent = listOf(ContentElement.Text("Real page content"))
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(realContent, "PDF", url)
        )
        
        viewModel.loadContent(url)
        advanceUntilIdle()

        // Try to update progress while index 0 is real content
        viewModel.updateReadingProgress(60, 60f, 0, 0)
        advanceUntilIdle()

        // Should HAVE called libraryRepository.saveProgress
        verify(libraryRepository).saveProgress(eq(itemId), any(), eq(60), any(), eq(60f), eq(0), eq(0))
    }

    @Test
    fun `computeAutoDeleteCandidates removes only completed chapters two or more behind`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Chapter 3", url = "url-3", currentChapter = "Chapter 3", baseTitle = "Novel", progress = 100),
            LibraryItem(id = "2", title = "Chapter 4", url = "url-4", currentChapter = "Chapter 4", baseTitle = "Novel", progress = 100),
            LibraryItem(id = "3", title = "Chapter 5", url = "url-5", currentChapter = "Chapter 5", baseTitle = "Novel", progress = 100),
            LibraryItem(id = "4", title = "Chapter 2", url = "url-2", currentChapter = "Chapter 2", baseTitle = "Novel", progress = 80),
            LibraryItem(id = "5", title = "Chapter 1", url = "url-1", currentChapter = "Chapter 1", baseTitle = "Other", progress = 100)
        )

        val toDelete = computeAutoDeleteCandidates(
            allItems = chapters,
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("1"), toDelete.map { it.id })
    }

    @Test
    fun `computeAutoDeleteCandidates keeps immediate previous and current chapters`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Chapter 5", url = "url-5", currentChapter = "Chapter 5", baseTitle = "Novel", progress = 100),
            LibraryItem(id = "2", title = "Chapter 6", url = "url-6", currentChapter = "Chapter 6", baseTitle = "Novel", progress = 100)
        )

        val toDelete = computeAutoDeleteCandidates(
            allItems = chapters,
            baseTitle = "Novel",
            currentUrl = "url-6",
            currentChapterNumber = 6.0
        )

        assertEquals(emptyList<LibraryItem>(), toDelete)
    }

    @Test
    fun `computeAutoDeleteCandidates skips chapters without parseable number`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Side story", url = "bonus", currentChapter = "Bonus", baseTitle = "Novel", progress = 100),
            LibraryItem(id = "2", title = "Chapter 3", url = "url-3", currentChapter = "Chapter 3", baseTitle = "Novel", progress = 100)
        )

        val toDelete = computeAutoDeleteCandidates(
            allItems = chapters,
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("2"), toDelete.map { it.id })
    }

    @Test
    fun `loadContent does not auto delete when current chapter number is not parseable`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/sidestory"
        val item = LibraryItem(
            id = itemId,
            title = "Novel - Bonus",
            url = url,
            currentChapter = "Bonus",
            baseTitle = "Novel",
            progress = 100
        )
        val existingItems = listOf(
            item,
            LibraryItem(
                id = "item-2",
                title = "Chapter 1",
                url = "https://example.com/ch1",
                currentChapter = "Chapter 1",
                baseTitle = "Novel",
                progress = 100
            )
        )

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(existingItems))
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Bonus content")), "Bonus", url)
        )
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(item)
        whenever(libraryRepository.getItemById(itemId)).thenReturn(item)

        viewModel = ReaderViewModel(
            contentRepository,
            libraryRepository,
            exploreRepository,
            preferencesManager
        )

        viewModel.loadContent(url)
        advanceTimeBy(1_000)
        advanceUntilIdle()

        verify(libraryRepository, never()).removeItems(any())
        verify(contentRepository, never()).clearCachesForUrls(any())
    }
}
