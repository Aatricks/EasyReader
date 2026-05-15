package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.CancellationException
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

    @Mock
    lateinit var chapterListCache: io.aatricks.easyreader.data.repository.ChapterListCache

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
            whenever(libraryRepository.updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true)
            whenever(libraryRepository.updateReadingMode(any(), any())).thenReturn(true)
            whenever(contentRepository.inspectCache(any())).thenAnswer { invocation ->
                PrefetchResult(
                    url = invocation.arguments[0] as String,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false
                )
            }
            whenever(contentRepository.prefetch(any(), any())).thenAnswer { invocation ->
                PrefetchResult(
                    url = invocation.arguments[0] as String,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false
                )
            }
            whenever(contentRepository.warmImage(any(), any())).thenReturn(true)
        }

        viewModel = ReaderViewModel(
            contentRepository,
            libraryRepository,
            exploreRepository,
            preferencesManager,
            chapterListCache
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
        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(initialItemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(initialUrl)),
            lastScrollProgress = any(),
            lastReadIndex = any(),
            lastReadOffset = any(),
            lastReadOffsetFraction = any()
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
    fun `openChapterFromStart ignores saved web progress`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-10"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 10",
            url = url,
            progress = 55,
            lastReadIndex = 4,
            lastReadOffset = 18,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.openChapterFromStart(url, itemId)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollIndex)
        assertEquals(0, viewModel.uiState.value.scrollOffset)
        assertEquals(0, viewModel.uiState.value.scrollProgress)
        assertEquals(0f, viewModel.uiState.value.scrollPosition, 0.001f)
    }

    @Test
    fun `loadContent restores saved web progress when navigation is not explicit`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-10"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 10",
            url = url,
            progress = 55,
            lastReadIndex = 4,
            lastReadOffset = 18,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.scrollIndex)
        assertEquals(18, viewModel.uiState.value.scrollOffset)
        assertEquals(55, viewModel.uiState.value.scrollProgress)
        assertEquals(55f, viewModel.uiState.value.scrollPosition, 0.001f)
    }

    @Test
    fun `loadContent defers raw offset when saved normalized anchor exists`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-10"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 10",
            url = url,
            progress = 55,
            lastReadIndex = 4,
            lastReadOffset = 180,
            lastReadOffsetFraction = 0.3f,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.scrollIndex)
        assertEquals(0, viewModel.uiState.value.scrollOffset)
        assertEquals(0.3f, viewModel.uiState.value.pendingRestoreOffsetFraction ?: 0f, 0.001f)
        assertEquals(55, viewModel.uiState.value.scrollProgress)
        assertEquals(55f, viewModel.uiState.value.scrollPosition, 0.001f)
    }

    @Test
    fun `loadContent snaps zero-progress saved web state to true top`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-11"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 11",
            url = url,
            progress = 0,
            lastReadIndex = 2,
            lastReadOffset = 24,
            lastScrollPosition = 5f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 11", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollIndex)
        assertEquals(0, viewModel.uiState.value.scrollOffset)
        assertEquals(0, viewModel.uiState.value.scrollProgress)
        assertEquals(0f, viewModel.uiState.value.scrollPosition, 0.001f)
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
        verify(libraryRepository, never()).updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())

        // Advance time
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()

        // Now it should have saved
        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = any(),
            lastReadIndex = eq(FieldUpdate.Set(5)),
            lastReadOffset = eq(FieldUpdate.Set(10)),
            lastReadOffsetFraction = any()
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
            offset = 100,
            firstVisibleItemSize = 100
        )
        val stateAfterFirstUpdate = viewModel.uiState.value

        viewModel.updateScrollPosition(
            scrollOffset = 30.1f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 2,
            offset = 103,
            firstVisibleItemSize = 100
        )

        val stateAfterSecondUpdate = viewModel.uiState.value
        assertEquals(stateAfterFirstUpdate.scrollOffset, stateAfterSecondUpdate.scrollOffset)
        assertEquals(stateAfterFirstUpdate.scrollPosition, stateAfterSecondUpdate.scrollPosition, 0.001f)

        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()

        verify(libraryRepository, times(1)).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = any(),
            lastReadIndex = eq(FieldUpdate.Set(2)),
            lastReadOffset = eq(FieldUpdate.Set(100)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(1f))
        )
    }

    @Test
    fun `updateScrollPosition updates progress state without mutating broad ui state`() = runTest {
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

        val uiStateBeforeScroll = viewModel.uiState.value

        viewModel.updateScrollPosition(
            scrollOffset = 50f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 5,
            offset = 10,
            firstVisibleItemSize = 100
        )

        val uiStateAfterScroll = viewModel.uiState.value
        val progressState = viewModel.progressState.value

        assertEquals(uiStateBeforeScroll.scrollPosition, uiStateAfterScroll.scrollPosition, 0.001f)
        assertEquals(uiStateBeforeScroll.scrollProgress, uiStateAfterScroll.scrollProgress)
        assertEquals(uiStateBeforeScroll.scrollIndex, uiStateAfterScroll.scrollIndex)
        assertEquals(uiStateBeforeScroll.scrollOffset, uiStateAfterScroll.scrollOffset)
        assertEquals(55, progressState.scrollProgress)
        assertEquals(55.555f, progressState.scrollPosition, 0.01f)
        assertEquals(5, progressState.scrollIndex)
        assertEquals(10, progressState.scrollOffset)
        assertEquals(0.1f, progressState.scrollOffsetFraction ?: 0f, 0.001f)
    }

    @Test
    fun `persistLifecycleProgress persists untouched chapter start as true top snapshot`() = runTest {
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

        viewModel.updateScrollPosition(
            scrollOffset = 5f,
            maxScrollOffset = 100f,
            viewportHeight = 0f,
            index = 1,
            offset = 15,
            firstVisibleItemSize = 100
        )

        viewModel.persistLifecycleProgress()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(0)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(0f)),
            lastReadIndex = eq(FieldUpdate.Set(0)),
            lastReadOffset = eq(FieldUpdate.Set(0)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0f))
        )
    }

    @Test
    fun `persistLifecycleProgress preserves restored anchor before user interaction`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/manwha/1"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 1",
            url = url,
            progress = 57,
            lastReadIndex = 3,
            lastReadOffset = 140,
            lastReadOffsetFraction = 0.35f,
            lastScrollPosition = 57f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Test")), "Test", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        clearInvocations(libraryRepository)

        viewModel.updateScrollPosition(
            scrollOffset = 60f,
            maxScrollOffset = 100f,
            viewportHeight = 0f,
            index = 4,
            offset = 320,
            firstVisibleItemSize = 800
        )

        viewModel.persistLifecycleProgress()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(57)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(57f)),
            lastReadIndex = eq(FieldUpdate.Set(3)),
            lastReadOffset = eq(FieldUpdate.Set(140)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.35f))
        )
    }

    @Test
    fun `persistLifecycleProgress preserves live snapshot after user interaction`() = runTest {
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
            scrollOffset = 12f,
            maxScrollOffset = 100f,
            viewportHeight = 0f,
            index = 2,
            offset = 30,
            firstVisibleItemSize = 120
        )
        advanceTimeBy(200)
        runCurrent()
        clearInvocations(libraryRepository)

        viewModel.persistLifecycleProgress()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(12)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(12f)),
            lastReadIndex = eq(FieldUpdate.Set(2)),
            lastReadOffset = eq(FieldUpdate.Set(30)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.25f))
        )
    }

    @Test
    fun `persistLifecycleProgress cancels pending debounced save to avoid overwrite`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/manhwa/10"

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Image("https://cdn.example.com/1.jpg")), "Chapter 10", url)
        )
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(
            LibraryItem(id = itemId, title = "Chapter 10", url = url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Chapter 10", url = url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()
        viewModel.onUserInteraction()
        clearInvocations(libraryRepository)

        viewModel.updateScrollPosition(
            scrollOffset = 33f,
            maxScrollOffset = 100f,
            viewportHeight = 0f,
            index = 5,
            offset = 120,
            firstVisibleItemSize = 300
        )

        viewModel.persistLifecycleProgress()
        advanceTimeBy(250)
        runCurrent()
        advanceUntilIdle()

        verify(libraryRepository, times(1)).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(33)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(33f)),
            lastReadIndex = eq(FieldUpdate.Set(5)),
            lastReadOffset = eq(FieldUpdate.Set(120)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.4f))
        )
    }

    @Test
    fun `loadContent restores manhwa index and offset fraction across reloads`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/webtoon/chapter-9"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 9",
            url = url,
            progress = 64,
            lastReadIndex = 5,
            lastReadOffset = 220,
            lastReadOffsetFraction = 0.4f,
            lastScrollPosition = 64f
        )
        val unknownDimensionContent = ContentResult.Success(
            elements = (1..8).map { ContentElement.Image("https://cdn.example.com/panel-$it.jpg") },
            title = "Chapter 9",
            url = url
        )
        val knownDimensionContent = ContentResult.Success(
            elements = (1..8).map { index ->
                ContentElement.Image(
                    url = "https://cdn.example.com/panel-$index.jpg",
                    width = 1080 + index,
                    height = 1920 + index
                )
            },
            title = "Chapter 9",
            url = url
        )

        whenever(contentRepository.loadContent(url)).thenReturn(unknownDimensionContent, knownDimensionContent)
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.scrollIndex)
        assertEquals(0.4f, viewModel.uiState.value.pendingRestoreOffsetFraction ?: 0f, 0.001f)
        assertEquals("https://cdn.example.com/panel-6.jpg", (viewModel.uiState.value.content?.paragraphs?.get(5) as ContentElement.Image).url)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.scrollIndex)
        assertEquals(0.4f, viewModel.uiState.value.pendingRestoreOffsetFraction ?: 0f, 0.001f)
        assertEquals("https://cdn.example.com/panel-6.jpg", (viewModel.uiState.value.content?.paragraphs?.get(5) as ContentElement.Image).url)
    }

    @Test
    fun `seekToProgress updates progress state and seek ui fields`() = runTest {
        viewModel.seekToProgress(42f)

        val progressState = viewModel.progressState.value
        val uiState = viewModel.uiState.value

        assertEquals(42, progressState.scrollProgress)
        assertEquals(42f, progressState.scrollPosition, 0.001f)
        assertEquals(42, uiState.scrollProgress)
        assertEquals(42f, uiState.scrollPosition, 0.001f)
        assertTrue(uiState.seekTrigger > 0L)
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

        // Should NOT have called libraryRepository.updateProgress
        verify(libraryRepository, never()).updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())

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

        // Should HAVE called libraryRepository.updateProgress
        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(60)),
            currentChapterUrl = any(),
            lastScrollProgress = eq(FieldUpdate.Set(60f)),
            lastReadIndex = eq(FieldUpdate.Set(0)),
            lastReadOffset = eq(FieldUpdate.Set(0)),
            lastReadOffsetFraction = any()
        )
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
            preferencesManager,
            chapterListCache
        )

        viewModel.loadContent(url)
        advanceTimeBy(1_000)
        advanceUntilIdle()

        verify(libraryRepository, never()).removeItems(any())
        verify(contentRepository, never()).clearCachesForUrls(any())
    }

    @Test
    fun `prefetchVisibleImage delegates to user-priority download`() = runTest {
        val imageUrl = "https://example.com/image.jpg"
        val pageUrl = "https://example.com/chapter-1"

        viewModel.prefetchVisibleImage(imageUrl, pageUrl)
        advanceUntilIdle()

        verify(contentRepository).downloadAndCacheImage(imageUrl, pageUrl)
    }

    @Test
    fun `switching chapters cancels old load without poisoning newer load`() = runTest {
        val firstUrl = "https://example.com/manhwa-1"
        val secondUrl = "https://example.com/manhwa-2"

        whenever(contentRepository.loadContent(any())).thenAnswer { invocation ->
            when (invocation.arguments[0] as String) {
                firstUrl -> throw CancellationException("Superseded by navigation")
                secondUrl -> ContentResult.Success(listOf(ContentElement.Text("new")), "New", secondUrl)
                else -> ContentResult.Error("Unexpected")
            }
        }

        viewModel.loadContent(firstUrl)
        runCurrent()
        viewModel.loadContent(secondUrl)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        assertEquals(secondUrl, viewModel.uiState.value.content?.url)
    }

    @Test
    fun `retryLoad resets web loader state before retrying`() = runTest {
        val url = "https://example.com/chapter-retry"
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Error("initial failure"),
            ContentResult.Success(listOf(ContentElement.Text("ok")), "Recovered", url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()
        viewModel.retryLoad()
        advanceUntilIdle()

        verify(contentRepository).resetWebLoadState(url, true)
        assertEquals(url, viewModel.uiState.value.content?.url)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
