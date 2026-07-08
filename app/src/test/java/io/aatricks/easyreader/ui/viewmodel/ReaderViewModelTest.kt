package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import io.aatricks.easyreader.util.FieldUpdate
import io.aatricks.easyreader.util.computeDownloadCleanup
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
import java.io.File

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
        whenever(preferencesManager.readerSettings).thenReturn(
            MutableStateFlow(
                io.aatricks.easyreader.data.local.ReaderSettingsSnapshot(
                    fontSize = 18f,
                    lineHeight = 1.5f,
                    fontFamily = "Default",
                    margins = 16,
                    verticalMargins = 0,
                    paragraphSpacing = 1.0f,
                    readerTheme = ReaderTheme.DARK.name,
                    accentTheme = io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name
                )
            )
        )

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
            chapterListCache,
            fakeImageDimensionCacheRepository()
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
        val initialItemId = "item-1"
        val initialUrl = "https://example.com/1"

        val result1 = ContentResult.Success(
            elements = listOf(ContentElement.Text("Initial body")),
            title = "Title 1",
            url = initialUrl
        )
        whenever(contentRepository.loadContent(initialUrl)).thenReturn(result1)
        whenever(libraryRepository.getItemByUrl(initialUrl)).thenReturn(
            LibraryItem(id = initialItemId, title = "Title 1", url = initialUrl, progress = 30, lastScrollPosition = 30f)
        )
        whenever(libraryRepository.getItemById(initialItemId)).thenReturn(
            LibraryItem(id = initialItemId, title = "Title 1", url = initialUrl, progress = 30, lastScrollPosition = 30f)
        )

        viewModel.loadContent(initialUrl)
        advanceUntilIdle()

        assertEquals(initialUrl, viewModel.uiState.value.content?.url)

        val nextUrl = "https://example.com/2"
        whenever(contentRepository.loadContent(nextUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Next body")), "Title 2", nextUrl)
        )

        viewModel.loadContent(nextUrl)
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(initialItemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(initialUrl)),
            lastScrollProgress = any(),
            lastReadIndex = any(),
            lastReadElementKey = any(),
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
            lastReadOffsetFraction = 0.3f,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.openChapterFromStart(url, itemId)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollIndex)
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
            lastReadOffsetFraction = 0.6f,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(List(10) { ContentElement.Text("Paragraph $it") }, "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.scrollIndex)
        assertEquals(55, viewModel.uiState.value.scrollProgress)
        assertEquals(55f, viewModel.uiState.value.scrollPosition, 0.001f)
        assertEquals(0.6f, viewModel.uiState.value.restoreOffsetFraction, 0.001f)
    }

    @Test
    fun `loadContent restores via element key when content is reparsed`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-10"
        val paragraphs = List(10) { ContentElement.Text("Paragraph $it") }
        val targetIndex = 6
        val targetKey = stableContentElementKey(url, targetIndex, paragraphs[targetIndex])

        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 10",
            url = url,
            progress = 55,
            // Wrong index — element key must override.
            lastReadIndex = 2,
            lastReadElementKey = targetKey,
            lastReadOffsetFraction = 0.4f,
            lastScrollPosition = 55f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(paragraphs, "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(targetIndex, viewModel.uiState.value.scrollIndex)
        assertEquals(targetKey, viewModel.uiState.value.restoreElementKey)
        assertEquals(0.4f, viewModel.uiState.value.restoreOffsetFraction, 0.001f)
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
            lastReadIndex = 0,
            lastReadOffsetFraction = FRACTION_UNKNOWN,
            lastScrollPosition = 0f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Chapter content")), "Chapter 11", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollIndex)
        assertEquals(0, viewModel.uiState.value.scrollProgress)
        assertEquals(0f, viewModel.uiState.value.scrollPosition, 0.001f)
    }

    @Test
    fun `lifecycle save preserves zero-progress precise anchor`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-12"
        val paragraphs = List(6) { ContentElement.Text("Paragraph $it") }
        val anchorIndex = 3
        val anchorKey = stableContentElementKey(url, anchorIndex, paragraphs[anchorIndex])
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 12",
            url = url,
            progress = 0,
            lastReadIndex = anchorIndex,
            lastReadElementKey = anchorKey,
            lastReadOffsetFraction = 0.25f,
            lastScrollPosition = 0f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(paragraphs, "Chapter 12", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        clearInvocations(libraryRepository)

        viewModel.persistLifecycleProgress()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(0)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(0f)),
            lastReadIndex = eq(FieldUpdate.Set(anchorIndex)),
            lastReadElementKey = eq(FieldUpdate.Set(anchorKey)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.25f))
        )
    }

    @Test
    fun `updateScrollPosition saves progress after delay`() = runTest {
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
            scrollOffset = 50f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 5,
            offsetFraction = 0.1f,
            elementKey = "txt:$url:5:foo",
            firstVisibleItemSize = 200
        )

        verify(libraryRepository, never()).updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())

        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = any(),
            lastReadIndex = eq(FieldUpdate.Set(5)),
            lastReadElementKey = eq(FieldUpdate.Set("txt:$url:5:foo")),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.1f))
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
            offsetFraction = 1.0f,
            elementKey = "txt:$url:2:foo",
            firstVisibleItemSize = 100
        )
        val progressAfterFirst = viewModel.progressState.value

        // Same index, fraction barely changes — should not write again.
        viewModel.updateScrollPosition(
            scrollOffset = 30.1f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 2,
            offsetFraction = 1.001f,
            elementKey = "txt:$url:2:foo",
            firstVisibleItemSize = 100
        )

        val progressAfterSecond = viewModel.progressState.value
        assertEquals(progressAfterFirst.scrollPosition, progressAfterSecond.scrollPosition, 0.001f)

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
            lastReadElementKey = eq(FieldUpdate.Set("txt:$url:2:foo")),
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
            offsetFraction = 0.1f,
            elementKey = "txt:$url:5:abc",
            firstVisibleItemSize = 100
        )

        val uiStateAfterScroll = viewModel.uiState.value
        val progressState = viewModel.progressState.value

        // Scroll updates push into progressState only — uiState's position-display fields stay
        // as the per-load initial values so the bottom bar doesn't fight active scrolling.
        assertEquals(uiStateBeforeScroll.scrollPosition, uiStateAfterScroll.scrollPosition, 0.001f)
        assertEquals(uiStateBeforeScroll.scrollProgress, uiStateAfterScroll.scrollProgress)
        assertEquals(uiStateBeforeScroll.scrollIndex, uiStateAfterScroll.scrollIndex)
        assertEquals(55, progressState.scrollProgress)
        assertEquals(55.555f, progressState.scrollPosition, 0.01f)
        assertEquals(5, progressState.scrollIndex)
        assertEquals("txt:$url:5:abc", progressState.scrollElementKey)
        assertEquals(0.1f, progressState.scrollOffsetFraction, 0.001f)
    }

    @Test
    fun `consumeRestoreAnchor returns frozen uiState anchor on genuine load`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-10"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 10",
            url = url,
            progress = 55,
            lastReadIndex = 4,
            lastReadOffsetFraction = 0.6f,
            lastScrollPosition = 55f
        )
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(List(10) { ContentElement.Text("Paragraph $it") }, "Chapter 10", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        val anchor = viewModel.consumeRestoreAnchor()
        val ui = viewModel.uiState.value
        assertFalse(anchor.isLiveSource)
        assertEquals(ui.scrollIndex, anchor.scrollIndex)
        assertEquals(ui.restoreElementKey, anchor.elementKey)
        assertEquals(ui.restoreOffsetFraction, anchor.offsetFraction, 0.001f)
    }

    @Test
    fun `consumeRestoreAnchor returns live progressState anchor on bare recomposition after scroll`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/1"
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Test")), "Test", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        // First consume = the genuine restore for this load (marks the request id consumed).
        viewModel.consumeRestoreAnchor()

        // onUserInteraction() first is load-bearing: it clears the restore/suppress gates so the
        // scroll sample actually reaches _progressState (otherwise this would pass vacuously).
        viewModel.onUserInteraction()
        viewModel.updateScrollPosition(
            scrollOffset = 50f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 5,
            offsetFraction = 0.1f,
            elementKey = "txt:$url:5:abc",
            firstVisibleItemSize = 100
        )

        // Second consume with no intervening load/seek = a bare recomposition (the library
        // round-trip). Must resolve to the LIVE scrolled position, not the frozen uiState.
        val anchor = viewModel.consumeRestoreAnchor()
        assertTrue(anchor.isLiveSource)
        assertEquals(5, anchor.scrollIndex)
        assertEquals("txt:$url:5:abc", anchor.elementKey)
        assertEquals(0.1f, anchor.offsetFraction, 0.001f)
        // The frozen uiState still holds the open-time top (index 0) — proves we did NOT read it.
        assertEquals(0, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun `consumeRestoreAnchor returns genuine anchor after seek`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/1"
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(List(10) { ContentElement.Text("Paragraph $it") }, "Test", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Test", url = url)
        )

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        viewModel.consumeRestoreAnchor() // consume the load's request id

        viewModel.seekToProgress(42f)

        // A seek bumps the restore-request id, so the seekTrigger-driven restore is genuine and
        // reads the fresh seek anchor from uiState. (Index can't discriminate the branch here,
        // because seekToProgress syncs uiState and progressState to the same values.)
        val anchor = viewModel.consumeRestoreAnchor()
        assertFalse(anchor.isLiveSource)
    }

    @Test
    fun `consumeRestoreAnchor hard-nulls targetScrollPosition on bare recomposition`() = runTest {
        val itemId = "web-item"
        val url = "https://example.com/chapter-end"
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter End",
            url = url,
            progress = 100,
            lastReadIndex = 9,
            lastScrollPosition = 100f
        )
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(List(10) { ContentElement.Text("Paragraph $it") }, "Chapter End", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()

        // Genuine branch honors the end-of-chapter one-shot.
        val genuine = viewModel.consumeRestoreAnchor()
        assertEquals(100f, genuine.targetScrollPosition!!, 0.001f)

        // User scrolls up, then returns from the library (bare recomposition).
        viewModel.onUserInteraction()
        viewModel.updateScrollPosition(
            scrollOffset = 30f,
            maxScrollOffset = 100f,
            viewportHeight = 10f,
            index = 3,
            offsetFraction = 0.0f,
            elementKey = "txt:$url:3:abc",
            firstVisibleItemSize = 100
        )
        val bare = viewModel.consumeRestoreAnchor()
        assertTrue(bare.isLiveSource)
        assertNull(bare.targetScrollPosition) // never yanked back to the chapter end
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

        // Note: no user interaction → restoredProgressSnapshot wins. Initial position = top.
        viewModel.persistLifecycleProgress()
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(0)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = eq(FieldUpdate.Set(0f)),
            lastReadIndex = eq(FieldUpdate.Set(0)),
            lastReadElementKey = eq(FieldUpdate.Set("")),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0f))
        )
    }

    @Test
    fun `persistLifecycleProgress preserves restored anchor before user interaction`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/manwha/1"
        val paragraphs = List(10) { ContentElement.Text("Paragraph $it") }
        val anchorKey = stableContentElementKey(url, 3, paragraphs[3])
        val savedItem = LibraryItem(
            id = itemId,
            title = "Chapter 1",
            url = url,
            progress = 57,
            lastReadIndex = 3,
            lastReadElementKey = anchorKey,
            lastReadOffsetFraction = 0.35f,
            lastScrollPosition = 57f
        )

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(paragraphs, "Test", url)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(savedItem)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        clearInvocations(libraryRepository)

        // Simulate a placeholder-sized measurement after restore — should NOT pollute persistence.
        viewModel.updateScrollPosition(
            scrollOffset = 60f,
            maxScrollOffset = 100f,
            viewportHeight = 0f,
            index = 4,
            offsetFraction = 0.4f,
            elementKey = "txt:other",
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
            lastReadElementKey = eq(FieldUpdate.Set(anchorKey)),
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
            offsetFraction = 0.25f,
            elementKey = "txt:live-anchor",
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
            lastReadElementKey = eq(FieldUpdate.Set("txt:live-anchor")),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.25f))
        )
    }

    @Test
    fun `persistLifecycleProgress cancels pending debounced save to avoid overwrite`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/manhwa/10"

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(
                // Declared dims so the lifecycle-save dim-known gate lets this through:
                // an unknown-dim image is treated as mid-reflow and rightly drops the save.
                listOf(ContentElement.Image(url = "https://cdn.example.com/1.jpg", width = 800, height = 1200)),
                "Chapter 10",
                url
            )
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
            index = 0,
            offsetFraction = 0.4f,
            elementKey = "img:https://cdn.example.com/1.jpg",
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
            lastReadIndex = eq(FieldUpdate.Set(0)),
            lastReadElementKey = eq(FieldUpdate.Set("img:https://cdn.example.com/1.jpg")),
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
            lastReadElementKey = "img:https://cdn.example.com/panel-6.jpg",
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
        assertEquals(0.4f, viewModel.uiState.value.restoreOffsetFraction, 0.001f)

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.scrollIndex)
        assertEquals(0.4f, viewModel.uiState.value.restoreOffsetFraction, 0.001f)
    }

    @Test
    fun `persistImageDimensions updates current chapter content`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/webtoon/chapter-10"
        val imageUrl = "https://cdn.example.com/panel-1.jpg"

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(
                elements = listOf(ContentElement.Image(imageUrl)),
                title = "Chapter 10",
                url = url
            )
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Chapter 10", url = url)
        )

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        assertEquals(0, (viewModel.uiState.value.content?.paragraphs?.single() as ContentElement.Image).width)

        viewModel.persistImageDimensions(imageUrl, 1080, 1920)
        advanceUntilIdle()

        val image = viewModel.uiState.value.content?.paragraphs?.single() as ContentElement.Image
        assertEquals(1080, image.width)
        assertEquals(1920, image.height)
    }

    @Test
    fun `navigating to a new chapter prunes previous chapter dimension state`() = runTest {
        val itemId = "item-1"
        val urlA = "https://example.com/webtoon/chapter-10"
        val urlB = "https://example.com/webtoon/chapter-11"
        val imageA = "https://cdn.example.com/panel-a.jpg"
        val imageB = "https://cdn.example.com/panel-b.jpg"

        whenever(contentRepository.loadContent(urlA)).thenReturn(
            ContentResult.Success(elements = listOf(ContentElement.Image(imageA)), title = "Chapter 10", url = urlA)
        )
        whenever(contentRepository.loadContent(urlB)).thenReturn(
            ContentResult.Success(elements = listOf(ContentElement.Image(imageB)), title = "Chapter 11", url = urlB)
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Chapter 10", url = urlA)
        )

        viewModel.loadContent(urlA, itemId)
        advanceUntilIdle()
        viewModel.persistImageDimensions(imageA, 1080, 1920)
        advanceUntilIdle()
        assertEquals(1080 to 1920, viewModel.imageDimensionState(imageA).value)

        viewModel.loadContent(urlB, itemId)
        advanceUntilIdle()

        assertNull(viewModel.imageDimensionState(imageA).value)
    }

    @Test
    fun `scroll progress saves when current item is stable even before upstream image resolves`() = runTest {
        val itemId = "item-1"
        val url = "https://example.com/webtoon/chapter-11"
        val imageUrl = "https://cdn.example.com/panel-1.jpg"
        val textKey = "txt:$url:1:after-image"

        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(
                elements = listOf(
                    ContentElement.Image(imageUrl),
                    ContentElement.Text("Text after image")
                ),
                title = "Chapter 11",
                url = url
            )
        )
        whenever(libraryRepository.getItemById(itemId)).thenReturn(
            LibraryItem(id = itemId, title = "Chapter 11", url = url)
        )

        viewModel.loadContent(url, itemId)
        advanceUntilIdle()
        viewModel.onUserInteraction()
        clearInvocations(libraryRepository)

        viewModel.updateScrollPosition(
            scrollOffset = 1.6f,
            maxScrollOffset = 10f,
            viewportHeight = 1f,
            index = 1,
            offsetFraction = 0.6f,
            elementKey = textKey,
            firstVisibleItemSize = 500
        )
        advanceTimeBy(200)
        runCurrent()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = any(),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = any(),
            lastReadIndex = eq(FieldUpdate.Set(1)),
            lastReadElementKey = eq(FieldUpdate.Set(textKey)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.6f))
        )
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

        viewModel.updateReadingProgress(50, 50f, 0, "", 0f)
        advanceUntilIdle()

        verify(libraryRepository, never()).updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())

        val realContent = listOf(ContentElement.Text("Real page content"))
        whenever(contentRepository.loadContent(url)).thenReturn(
            ContentResult.Success(realContent, "PDF", url)
        )

        viewModel.loadContent(url)
        advanceUntilIdle()

        viewModel.updateReadingProgress(60, 60f, 0, "txt:$url:0:abc", 0f)
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq(itemId),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(60)),
            currentChapterUrl = any(),
            lastScrollProgress = eq(FieldUpdate.Set(60f)),
            lastReadIndex = eq(FieldUpdate.Set(0)),
            lastReadElementKey = eq(FieldUpdate.Set("txt:$url:0:abc")),
            lastReadOffsetFraction = any()
        )
    }

    @Test
    fun `computeDownloadCleanup frees only downloaded read chapters two or more behind`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Chapter 3", url = "url-3", currentChapter = "Chapter 3", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "2", title = "Chapter 4", url = "url-4", currentChapter = "Chapter 4", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "3", title = "Chapter 5", url = "url-5", currentChapter = "Chapter 5", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "4", title = "Chapter 2", url = "url-2", currentChapter = "Chapter 2", baseTitle = "Novel", progress = 80, isDownloaded = true),
            LibraryItem(id = "5", title = "Chapter 1", url = "url-1", currentChapter = "Chapter 1", baseTitle = "Other", progress = 100, isDownloaded = true)
        )

        val plan = computeDownloadCleanup(
            allItems = chapters,
            fullChapterList = emptyList(),
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("1"), plan.downloadsToFree.map { it.id })
    }

    @Test
    fun `computeDownloadCleanup keeps immediate previous and current chapters`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Chapter 5", url = "url-5", currentChapter = "Chapter 5", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "2", title = "Chapter 6", url = "url-6", currentChapter = "Chapter 6", baseTitle = "Novel", progress = 100, isDownloaded = true)
        )

        val plan = computeDownloadCleanup(
            allItems = chapters,
            fullChapterList = emptyList(),
            baseTitle = "Novel",
            currentUrl = "url-6",
            currentChapterNumber = 6.0
        )

        assertEquals(emptyList<String>(), plan.downloadsToFree.map { it.id })
    }

    @Test
    fun `computeDownloadCleanup frees only downloaded chapters`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Chapter 1", url = "url-1", currentChapter = "Chapter 1", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "2", title = "Chapter 2", url = "url-2", currentChapter = "Chapter 2", baseTitle = "Novel", progress = 100, isDownloaded = false),
            LibraryItem(id = "3", title = "Chapter 3", url = "url-3", currentChapter = "Chapter 3", baseTitle = "Novel", progress = 100, isDownloaded = false)
        )

        val plan = computeDownloadCleanup(
            allItems = chapters,
            fullChapterList = emptyList(),
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("1"), plan.downloadsToFree.map { it.id })
    }

    @Test
    fun `computeDownloadCleanup uses the 90 percent finished threshold`() {
        val chapters = listOf(
            LibraryItem(id = "ok", title = "Chapter 1", url = "url-1", currentChapter = "Chapter 1", baseTitle = "Novel", progress = 90, isDownloaded = true),
            LibraryItem(id = "no", title = "Chapter 2", url = "url-2", currentChapter = "Chapter 2", baseTitle = "Novel", progress = 89, isDownloaded = true)
        )

        val plan = computeDownloadCleanup(
            allItems = chapters,
            fullChapterList = emptyList(),
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("ok"), plan.downloadsToFree.map { it.id })
    }

    @Test
    fun `computeDownloadCleanup skips chapters without parseable number`() {
        val chapters = listOf(
            LibraryItem(id = "1", title = "Side story", url = "bonus", currentChapter = "Bonus", baseTitle = "Novel", progress = 100, isDownloaded = true),
            LibraryItem(id = "2", title = "Chapter 3", url = "url-3", currentChapter = "Chapter 3", baseTitle = "Novel", progress = 100, isDownloaded = true)
        )

        val plan = computeDownloadCleanup(
            allItems = chapters,
            fullChapterList = emptyList(),
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("2"), plan.downloadsToFree.map { it.id })
    }

    @Test
    fun `computeDownloadCleanup evicts speculative caches for non-library chapters far behind`() {
        val library = listOf(
            LibraryItem(id = "1", title = "Chapter 5", url = "url-5", currentChapter = "Chapter 5", baseTitle = "Novel", progress = 100, isDownloaded = true)
        )
        val fullList = listOf(
            ChapterInfo(title = "Chapter 1", url = "url-1", number = 1.0),
            ChapterInfo(title = "Chapter 4", url = "url-4", number = 4.0),
            ChapterInfo(title = "Chapter 5", url = "url-5", number = 5.0),
            ChapterInfo(title = "Chapter 2", url = "url-2", number = 2.0)
        )

        val plan = computeDownloadCleanup(
            allItems = library,
            fullChapterList = fullList,
            baseTitle = "Novel",
            currentUrl = "url-5",
            currentChapterNumber = 5.0
        )

        assertEquals(listOf("url-1", "url-2"), plan.speculativeCacheUrls.sorted())
        assertEquals(emptyList<String>(), plan.downloadsToFree.map { it.id })
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
            chapterListCache,
            fakeImageDimensionCacheRepository()
        )

        viewModel.loadContent(url)
        advanceTimeBy(1_000)
        advanceUntilIdle()

        verify(libraryRepository, never()).markDownloaded(any(), any())
        verify(contentRepository, never()).clearCachesAndDownloadsForUrls(any())
        verify(contentRepository, never()).clearCachesForUrls(any())
        verify(libraryRepository, never()).removeItems(any())
    }

    @Test
    fun `loadContent frees old downloaded read chapters but keeps their library rows`() = runTest {
        val currentUrl = "https://example.com/ch5"
        val currentItem = LibraryItem(
            id = "item-5",
            title = "Chapter 5",
            url = currentUrl,
            currentChapter = "Chapter 5",
            baseTitle = "Novel",
            progress = 100,
            isDownloaded = true
        )
        fun chapter(n: Int) = LibraryItem(
            id = "item-$n",
            title = "Chapter $n",
            url = "https://example.com/ch$n",
            currentChapter = "Chapter $n",
            baseTitle = "Novel",
            progress = 100,
            isDownloaded = true
        )
        val existingItems = listOf(chapter(1), chapter(2), chapter(3), chapter(4), currentItem)

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(existingItems))
        whenever(contentRepository.loadContent(currentUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Body")), "Chapter 5", currentUrl)
        )
        whenever(libraryRepository.getItemByUrl(currentUrl)).thenReturn(currentItem)
        whenever(libraryRepository.getItemById("item-5")).thenReturn(currentItem)
        whenever(contentRepository.isUserDownloadInFlight(any())).thenReturn(false)
        whenever(libraryRepository.markDownloaded(any(), any())).thenReturn(true)

        viewModel = ReaderViewModel(
            contentRepository,
            libraryRepository,
            exploreRepository,
            preferencesManager,
            chapterListCache,
            fakeImageDimensionCacheRepository()
        )

        viewModel.loadContent(currentUrl)
        advanceTimeBy(1_000)
        advanceUntilIdle()

        // Chapters 1-3 are read and 2+ behind -> downloads freed; chapter 4 (n-1) is kept.
        verify(contentRepository).clearCachesAndDownloadsForUrls(
            check { urls ->
                assertEquals(
                    listOf("https://example.com/ch1", "https://example.com/ch2", "https://example.com/ch3"),
                    urls.toList().sorted()
                )
            }
        )
        verify(libraryRepository).markDownloaded("item-1", false)
        verify(libraryRepository).markDownloaded("item-2", false)
        verify(libraryRepository).markDownloaded("item-3", false)
        verify(libraryRepository, never()).markDownloaded("item-4", false)
        // The rows themselves are preserved; only the download flag is cleared.
        verify(libraryRepository, never()).removeItems(any())
    }

    @Test
    fun `prefetchVisibleImage delegates to speculative warm`() = runTest {
        val imageUrl = "https://example.com/image.jpg"
        val pageUrl = "https://example.com/chapter-1"

        viewModel.prefetchVisibleImage(imageUrl, pageUrl)
        advanceUntilIdle()

        verify(contentRepository).warmImage(imageUrl, pageUrl)
    }

    @Test
    fun `repairVisibleImageNow invalidates local file demotes downloaded chapter and refetches`() = runTest {
        val imageUrl = "https://example.com/image.jpg"
        val pageUrl = "https://example.com/chapter-1"
        val item = LibraryItem(
            id = "item-1",
            title = "Chapter 1",
            url = pageUrl,
            isDownloaded = true
        )
        val repairedFile = File("repaired-image")

        whenever(libraryRepository.getItemByUrl(pageUrl)).thenReturn(item)
        whenever(libraryRepository.markDownloaded(item.id, false)).thenReturn(true)
        whenever(contentRepository.downloadAndCacheImage(imageUrl, pageUrl)).thenReturn(repairedFile)

        assertTrue(viewModel.repairVisibleImageNow(imageUrl, pageUrl))

        verify(contentRepository).invalidateCachedMediaFile(imageUrl, pageUrl)
        verify(libraryRepository).markDownloaded(item.id, false)
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
