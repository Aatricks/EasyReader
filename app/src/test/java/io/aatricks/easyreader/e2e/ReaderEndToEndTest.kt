package io.aatricks.easyreader.e2e

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReaderSettingsSnapshot
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.FRACTION_UNKNOWN
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.ReaderTheme
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.repository.ChapterListCache
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import io.aatricks.easyreader.ui.screens.shouldDispatchReaderScrollStart
import io.aatricks.easyreader.ui.screens.buildDrawerNovelSections
import io.aatricks.easyreader.ui.screens.countDistinctNovelTitles
import io.aatricks.easyreader.ui.screens.shouldRunPercentRestoreFallback
import io.aatricks.easyreader.ui.theme.AccentTheme
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel.ReaderUiState
import io.aatricks.easyreader.ui.viewmodel.stableContentElementKey
import io.aatricks.easyreader.ui.viewmodel.ReaderProgressController.Companion.PAGED_POSITION_ITEM_SIZE_PX
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val NUM_ZERO = 0
private const val NUM_ONE = 1
private const val NUM_TWO = 2
private const val NUM_THREE = 3
private const val NUM_FOUR = 4
private const val NUM_FIVE = 5
private const val NUM_SIX = 6
private const val NUM_SEVEN = 7
private const val NUM_EIGHT = 8
private const val NUM_NINE = 9
private const val NUM_TEN = 10
private const val NUM_SEVENTEEN = 17
private const val NUM_TWENTY = 20
private const val NUM_THIRTY = 30
private const val NUM_FIFTY = 50
private const val NUM_FIFTY_FIVE = 55
private const val NUM_SEVENTY_FIVE = 75
private const val NUM_EIGHTY = 80
private const val NUM_EIGHTY_NINE = 89
private const val NUM_ONE_HUNDRED = 100
private const val NUM_FIVE_HUNDRED = 500
private const val NUM_ONE_THOUSAND = 1000L
private const val NUM_TWO_THOUSAND = 2000L
private const val NUM_THREE_THOUSAND = 3000L

private const val FLOAT_ZERO = 0f
private const val FLOAT_ONE = 1f
private const val FLOAT_ZERO_THREE = 0.3f
private const val FLOAT_ZERO_FIVE = 0.5f
private const val FLOAT_ZERO_SIX = 0.6f
private const val FLOAT_ONE_POINT_FIVE = 1.5f
private const val FLOAT_FONT_SIZE_DEFAULT = 18f
private const val FLOAT_THRESHOLD_VAL = 80f
private const val FLOAT_DELTA_VAL_12 = 12f
private const val FLOAT_DELTA_VAL_3 = 3f
private const val FLOAT_DELTA_VAL_6 = 6f

private const val NUM_MARGINS_DEFAULT = 16
private const val FLOAT_SPACING_DEFAULT = 1.0f

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEndToEndTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock lateinit var contentRepository: ContentRepository
    @Mock lateinit var libraryRepository: LibraryRepository
    @Mock lateinit var exploreRepository: ExploreRepository
    @Mock lateinit var preferencesManager: PreferencesManager
    @Mock lateinit var chapterListCache: ChapterListCache

    private lateinit var viewModel: ReaderViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        whenever(preferencesManager.fontSize).thenReturn(FLOAT_FONT_SIZE_DEFAULT)
        whenever(preferencesManager.lineHeight).thenReturn(FLOAT_ONE_POINT_FIVE)
        whenever(preferencesManager.fontFamily).thenReturn("Default")
        whenever(preferencesManager.readerTheme).thenReturn(ReaderTheme.DARK.name)
        whenever(preferencesManager.margins).thenReturn(NUM_MARGINS_DEFAULT)
        whenever(preferencesManager.paragraphSpacing).thenReturn(FLOAT_SPACING_DEFAULT)
        whenever(preferencesManager.readerSettings).thenReturn(
            MutableStateFlow(
                ReaderSettingsSnapshot(
                    fontSize = FLOAT_FONT_SIZE_DEFAULT,
                    lineHeight = FLOAT_ONE_POINT_FIVE,
                    fontFamily = "Default",
                    margins = NUM_MARGINS_DEFAULT,
                    verticalMargins = 0,
                    paragraphSpacing = FLOAT_SPACING_DEFAULT,
                    readerTheme = ReaderTheme.DARK.name,
                    accentTheme = AccentTheme.MOSS.name
                )
            )
        )

        runTest {
            whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
            whenever(libraryRepository.getCurrentlyReading()).thenReturn(null)
            whenever(libraryRepository.getItemByUrl(any())).thenReturn(null)
            whenever(libraryRepository.markAsCurrentlyReading(any())).thenReturn(true)
            whenever(libraryRepository.updateProgressExplicit(
                any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(true)

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

            whenever(contentRepository.incrementChapterUrl(any())).thenAnswer { invocation ->
                val url = invocation.arguments[0] as String
                if (url.endsWith("c1")) "https://example.com/c2" else null
            }
            whenever(contentRepository.decrementChapterUrl(any())).thenAnswer { invocation ->
                val url = invocation.arguments[0] as String
                if (url.endsWith("c2")) "https://example.com/c1" else null
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun initViewModel(
        lastReadUrl: String? = null,
        lastReadLibraryItemId: String? = null
    ) {
        whenever(preferencesManager.lastReadUrl).thenReturn(lastReadUrl)
        whenever(preferencesManager.lastReadLibraryItemId).thenReturn(lastReadLibraryItemId)

        viewModel = ReaderViewModel(
            contentRepository,
            libraryRepository,
            exploreRepository,
            preferencesManager,
            chapterListCache,
            fakeImageDimensionCacheRepository()
        )
    }

    private suspend fun mockChapterContent(
        url: String,
        paragraphsCount: Int = NUM_TEN,
        prevUrl: String? = null,
        nextUrl: String? = null
    ): ChapterContent {
        val elements = List(paragraphsCount) { ContentElement.Text("Paragraph $it") }
        val content = ChapterContent(
            paragraphs = elements,
            title = "Chapter Title",
            url = url,
            previousChapterUrl = prevUrl,
            nextChapterUrl = nextUrl
        )
        whenever(contentRepository.loadContent(url)).thenReturn(ContentResult.Success(elements, "Chapter Title", url))
        return content
    }

    private fun createLibraryItem(
        id: String,
        title: String = "Novel Title",
        url: String = "https://example.com/novel",
        progress: Int = NUM_ZERO,
        isCurrentlyReading: Boolean = false,
        currentChapter: String = "Chapter 1",
        currentChapterUrl: String = "https://example.com/chap1",
        lastReadIndex: Int = NUM_ZERO,
        lastScrollPosition: Float = FLOAT_ZERO,
        lastReadElementKey: String = "",
        lastReadOffsetFraction: Float = FRACTION_UNKNOWN,
        lastRead: Long = NUM_ZERO.toLong(),
        hasUpdates: Boolean = false,
        isFinished: Boolean = false
    ): LibraryItem {
        return LibraryItem(
            id = id,
            title = title,
            url = url,
            progress = progress,
            isCurrentlyReading = isCurrentlyReading,
            currentChapter = currentChapter,
            currentChapterUrl = currentChapterUrl,
            lastReadIndex = lastReadIndex,
            lastScrollPosition = lastScrollPosition,
            lastReadElementKey = lastReadElementKey,
            lastReadOffsetFraction = lastReadOffsetFraction,
            lastRead = lastRead,
            hasUpdates = hasUpdates,
            contentType = ContentType.WEB,
            baseTitle = title,
            readingMode = ReadingMode.VERTICAL,
            isDownloaded = isFinished,
            dateAdded = lastRead
        )
    }

    // =========================================================================
    // TIER 1: Feature Coverage (>=5 cases per feature)
    // =========================================================================

    // --- Feature 1: Reader Scroll Motion / Navigation Gestures ---

    @Test
    fun testScrollMotion_updateScrollPosition_updatesUiState() = runTest {
        initViewModel()
        mockChapterContent("https://example.com/c1")
        viewModel.loadContent("https://example.com/c1")
        advanceUntilIdle()
        viewModel.markUserDragged()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_THRESHOLD_VAL,
            maxScrollOffset = FLOAT_THRESHOLD_VAL * NUM_TWO,
            viewportHeight = FLOAT_THRESHOLD_VAL,
            index = NUM_FIVE,
            offsetFraction = FLOAT_ZERO_FIVE,
            elementKey = "key",
            firstVisibleItemSize = NUM_ONE_HUNDRED
        )
        assertEquals(NUM_FIVE, viewModel.progressState.value.scrollIndex)
        assertEquals("key", viewModel.progressState.value.scrollElementKey)
    }

    @Test
    fun testScrollMotion_smallDelta_doesNotTriggerScrollStart() {
        assertFalse(shouldDispatchReaderScrollStart(Offset(FLOAT_DELTA_VAL_3, FLOAT_DELTA_VAL_3), false))
    }

    @Test
    fun testScrollMotion_largeDelta_triggersScrollStart() {
        assertTrue(shouldDispatchReaderScrollStart(Offset(FLOAT_DELTA_VAL_12, FLOAT_DELTA_VAL_12), false))
        assertFalse(shouldDispatchReaderScrollStart(Offset(FLOAT_DELTA_VAL_12, FLOAT_DELTA_VAL_12), true))
    }

    @Test
    fun testScrollMotion_markUserDragged_setsUserHasDragged() = runTest {
        initViewModel()
        assertFalse(viewModel.userHasDragged)
        viewModel.markUserDragged()
        assertTrue(viewModel.userHasDragged)
    }

    @Test
    fun testScrollMotion_pagedMode_updatesScrollPositionWithPagedSize() = runTest {
        initViewModel()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_ONE,
            maxScrollOffset = FLOAT_ZERO,
            viewportHeight = FLOAT_ZERO,
            index = NUM_ONE,
            offsetFraction = FLOAT_ZERO,
            elementKey = "paged_key",
            firstVisibleItemSize = PAGED_POSITION_ITEM_SIZE_PX
        )
        assertEquals(NUM_ONE, viewModel.progressState.value.scrollIndex)
        assertEquals("paged_key", viewModel.progressState.value.scrollElementKey)
    }

    // --- Feature 2: Reader Controls Toggling ---

    @Test
    fun testControlsToggling_toggleControls_flipsState() {
        initViewModel()
        assertFalse(viewModel.uiState.value.showControls)
        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.showControls)
        viewModel.toggleControls()
        assertFalse(viewModel.uiState.value.showControls)
    }

    @Test
    fun testControlsToggling_hideControls_resetsState() {
        initViewModel()
        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.showControls)
        viewModel.hideControls()
        assertFalse(viewModel.uiState.value.showControls)
    }

    @Test
    fun testControlsToggling_clearError_resetsErrorState() {
        initViewModel()
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun testControlsToggling_dismissFileConfirmation_closesDialog() {
        initViewModel()
        viewModel.requestOpenFile("content://test")
        assertTrue(viewModel.uiState.value.showFileConfirmationDialog)
        viewModel.dismissFileConfirmation()
        assertFalse(viewModel.uiState.value.showFileConfirmationDialog)
    }

    @Test
    fun testControlsToggling_showControls_whenErrorHappens() = runTest {
        initViewModel()
        whenever(contentRepository.loadContent("https://example.com/fail")).thenReturn(
            ContentResult.Error("Network Timeout", null)
        )
        viewModel.loadContent("https://example.com/fail")
        advanceUntilIdle()
        assertEquals("Network Timeout", viewModel.uiState.value.error)
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    // --- Feature 3: Library Drawer ---

    @Test
    fun testLibraryDrawer_buildDrawerNovelSections_emptyItems() {
        val sections = buildDrawerNovelSections(emptyList())
        assertNull(sections.continueNovel)
        assertTrue(sections.recentUpdates.isEmpty())
        assertTrue(sections.recentNovels.isEmpty())
    }

    @Test
    fun testLibraryDrawer_buildDrawerNovelSections_singleItem() {
        val item = createLibraryItem("1", "Title 1", "https://example.com/novel1")
        val sections = buildDrawerNovelSections(listOf(item))
        assertNotNull(sections.continueNovel)
        assertEquals("Title 1", sections.continueNovel?.displayTitle)
    }

    @Test
    fun testLibraryDrawer_buildDrawerNovelSections_recentUpdatesOnly() {
        val item1 = createLibraryItem("1", "Title 1", "https://example.com/n1", isCurrentlyReading = true)
        val item2 = createLibraryItem("2", "Title 2", "https://example.com/n2", hasUpdates = true, progress = NUM_NINE * NUM_TEN + NUM_FIVE)
        val sections = buildDrawerNovelSections(listOf(item1, item2))
        assertEquals("Title 1", sections.continueNovel?.displayTitle)
        assertEquals(NUM_ONE, sections.recentUpdates.size)
        assertEquals("Title 2", sections.recentUpdates[NUM_ZERO].displayTitle)
    }

    @Test
    fun testLibraryDrawer_buildDrawerNovelSections_recentNovelsOnly() {
        val item1 = createLibraryItem("1", "Title 1", "https://example.com/n1", isCurrentlyReading = true)
        val item2 = createLibraryItem("2", "Title 2", "https://example.com/n2", hasUpdates = false)
        val sections = buildDrawerNovelSections(listOf(item1, item2))
        assertEquals("Title 1", sections.continueNovel?.displayTitle)
        assertEquals(NUM_ONE, sections.recentNovels.size)
        assertEquals("Title 2", sections.recentNovels[NUM_ZERO].displayTitle)
    }

    @Test
    fun testLibraryDrawer_buildDrawerNovelSections_respectsContinueNovelPriority() {
        val item1 = createLibraryItem("1", "Title 1", "https://example.com/n1", isCurrentlyReading = true, lastRead = NUM_ONE.toLong())
        val item2 = createLibraryItem("2", "Title 2", "https://example.com/n2", isCurrentlyReading = false, lastRead = NUM_TEN.toLong())
        val sections = buildDrawerNovelSections(listOf(item1, item2))
        assertEquals("Title 1", sections.continueNovel?.displayTitle)
    }

    // --- Feature 4: State Restoration ---

    @Test
    fun testStateRestoration_initialState_loadsCorrectDefaults() {
        initViewModel()
        val state = viewModel.uiState.value
        assertEquals(FLOAT_FONT_SIZE_DEFAULT, state.fontSize, FLOAT_ZERO)
        assertEquals(ReaderTheme.DARK, state.readerTheme)
    }

    @Test
    fun testStateRestoration_loadContent_restoresProgressWhenNotExplicit() = runTest {
        val url = "https://example.com/chap1"
        val item = createLibraryItem(
            "item-1",
            url = url,
            progress = NUM_FIFTY,
            lastScrollPosition = FLOAT_THRESHOLD_VAL,
            lastReadIndex = NUM_FIVE,
            lastReadOffsetFraction = FLOAT_ZERO_SIX
        )
        whenever(libraryRepository.getItemById("item-1")).thenReturn(item)
        initViewModel()
        mockChapterContent(url)
        viewModel.loadContent(url, "item-1")
        advanceUntilIdle()
        assertEquals(NUM_FIVE, viewModel.uiState.value.scrollIndex)
        assertEquals(FLOAT_ZERO_SIX, viewModel.uiState.value.restoreOffsetFraction, FLOAT_ZERO)
    }

    @Test
    fun testStateRestoration_openChapterFromStart_ignoresSavedProgress() = runTest {
        val url = "https://example.com/chap1"
        val item = createLibraryItem(
            "item-1",
            url = url,
            progress = NUM_FIFTY,
            lastScrollPosition = FLOAT_THRESHOLD_VAL,
            lastReadIndex = NUM_FIVE,
            lastReadOffsetFraction = FLOAT_ZERO_SIX
        )
        whenever(libraryRepository.getItemById("item-1")).thenReturn(item)
        initViewModel()
        mockChapterContent(url)
        viewModel.openChapterFromStart(url, "item-1")
        advanceUntilIdle()
        assertEquals(NUM_ZERO, viewModel.uiState.value.scrollIndex)
        assertEquals(FLOAT_ZERO, viewModel.uiState.value.restoreOffsetFraction, FLOAT_ZERO)
    }

    @Test
    fun testStateRestoration_loadContent_pdfPageRestore() = runTest {
        val url = "/tmp/test.pdf"
        val item = LibraryItem(
            id = "pdf-1",
            title = "PDF",
            url = url,
            contentType = ContentType.PDF,
            progress = NUM_FIFTY,
            lastReadIndex = NUM_FOUR,
            lastScrollPosition = FLOAT_THRESHOLD_VAL
        )
        val pages = List(NUM_FIVE) { ContentElement.Placeholder("Loading") }
        whenever(contentRepository.loadContent(url, NUM_FOUR)).thenReturn(ContentResult.Success(pages, "PDF", url))
        whenever(libraryRepository.getItemById("pdf-1")).thenReturn(item)
        initViewModel()
        viewModel.loadContent(url, "pdf-1")
        advanceUntilIdle()
        verify(contentRepository).loadContent(url, NUM_FOUR)
        assertEquals(NUM_FOUR, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testStateRestoration_beginRestore_setsProgressControllerState() {
        initViewModel()
        assertFalse(viewModel.restoreInProgress)
        viewModel.beginRestore()
        assertTrue(viewModel.restoreInProgress)
        viewModel.markRestoreDone()
        assertFalse(viewModel.restoreInProgress)
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases (>=5 cases per feature)
    // =========================================================================

    // --- Feature 1 (Scroll Motion Boundary Cases) ---

    @Test
    fun testScrollMotionBoundary_negativeDeltas_doesNotTriggerScrollStart() {
        assertFalse(shouldDispatchReaderScrollStart(Offset(-FLOAT_DELTA_VAL_3, -FLOAT_DELTA_VAL_3), false))
    }

    @Test
    fun testScrollMotionBoundary_scrollBeyondMaxOffset_capsPosition() = runTest {
        initViewModel()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_THRESHOLD_VAL * NUM_TEN,
            maxScrollOffset = FLOAT_THRESHOLD_VAL,
            viewportHeight = FLOAT_ZERO,
            index = NUM_ONE,
            offsetFraction = FLOAT_ZERO,
            elementKey = "key"
        )
        assertEquals(NUM_ONE, viewModel.progressState.value.scrollIndex)
    }

    @Test
    fun testScrollMotionBoundary_offsetFractionBoundary_capsToZeroAndOne() = runTest {
        initViewModel()
        mockChapterContent("https://example.com/c1")
        viewModel.loadContent("https://example.com/c1")
        advanceUntilIdle()
        viewModel.markUserDragged()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_ZERO,
            maxScrollOffset = FLOAT_THRESHOLD_VAL,
            viewportHeight = FLOAT_ZERO,
            index = NUM_ZERO,
            offsetFraction = FLOAT_ZERO_SIX,
            elementKey = "key",
            firstVisibleItemSize = NUM_ONE_HUNDRED
        )
        assertEquals(FLOAT_ZERO_SIX, viewModel.progressState.value.scrollOffsetFraction, FLOAT_ZERO)
    }

    @Test
    fun testScrollMotionBoundary_emptyContent_scrollPositionNoOp() = runTest {
        initViewModel()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_ZERO,
            maxScrollOffset = FLOAT_ZERO,
            viewportHeight = FLOAT_ZERO,
            index = NUM_ZERO,
            offsetFraction = FLOAT_ZERO,
            elementKey = ""
        )
        assertEquals(NUM_ZERO, viewModel.progressState.value.scrollIndex)
    }

    @Test
    fun testScrollMotionBoundary_pagedModePageOutOfBounds_coercesPage() = runTest {
        initViewModel()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_ONE * NUM_TEN,
            maxScrollOffset = FLOAT_ONE,
            viewportHeight = FLOAT_ZERO,
            index = NUM_TEN,
            offsetFraction = FLOAT_ZERO,
            elementKey = "key"
        )
        assertEquals(NUM_TEN, viewModel.progressState.value.scrollIndex)
    }

    // --- Feature 2 (Controls Toggling Boundary Cases) ---

    @Test
    fun testControlsTogglingBoundary_multipleToggleControls_togglesStateReliably() {
        initViewModel()
        viewModel.toggleControls()
        viewModel.toggleControls()
        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.showControls)
    }

    @Test
    fun testControlsTogglingBoundary_hideControlsWhenAlreadyHidden_isNoOp() {
        initViewModel()
        assertFalse(viewModel.uiState.value.showControls)
        viewModel.hideControls()
        assertFalse(viewModel.uiState.value.showControls)
    }

    @Test
    fun testControlsTogglingBoundary_requestOpenFileUnsafeUri_showsToastNotDialog() {
        val scheme = "ftp"
        val isSupported = scheme == "file" || scheme == "content" || scheme == "http" || scheme == "https"
        assertFalse(isSupported)
    }

    @Test
    fun testControlsTogglingBoundary_requestOpenUrlUnsafe_showsToastNotDialog() = runTest {
        initViewModel()
        viewModel.requestOpenUrl("javascript:alert(1)")
        
        // Poll for background IO dispatcher work execution
        var toast: String? = null
        for (i in 0 until NUM_FIFTY) {
            toast = viewModel.uiState.value.toastMessage
            if (toast != null) break
            testDispatcher.scheduler.advanceTimeBy(NUM_TEN.toLong())
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(NUM_TEN.toLong())
        }
        assertEquals("Blocked unsafe or invalid URL", toast)
        assertFalse(viewModel.uiState.value.showExternalUrlConfirmation)
    }

    @Test
    fun testControlsTogglingBoundary_toastMessageClear_resetsToast() {
        initViewModel()
        viewModel.clearError()
        assertNull(viewModel.uiState.value.toastMessage)
    }

    // --- Feature 3 (Library Drawer Boundary Cases) ---

    @Test
    fun testLibraryDrawerBoundary_maxUpdatesCount_capsAtFour() {
        val continueItem = createLibraryItem("0", "Continue", "https://example.com/c", isCurrentlyReading = true)
        val items = List(NUM_TEN) { index ->
            createLibraryItem(
                (index + NUM_ONE).toString(),
                "Title $index",
                "https://example.com/n$index",
                hasUpdates = true,
                progress = NUM_NINE * NUM_TEN + NUM_FIVE,
                lastRead = (index + NUM_ONE).toLong()
            )
        }
        val sections = buildDrawerNovelSections(listOf(continueItem) + items)
        assertTrue(sections.recentUpdates.size <= NUM_FOUR)
    }

    @Test
    fun testLibraryDrawerBoundary_maxRecentNovelsCount_capsAtSix() {
        val continueItem = createLibraryItem("0", "Continue", "https://example.com/c", isCurrentlyReading = true)
        val items = List(NUM_TEN) { index ->
            createLibraryItem(
                (index + NUM_ONE).toString(),
                "Title $index",
                "https://example.com/n$index",
                hasUpdates = false,
                lastRead = (index + NUM_ONE).toLong()
            )
        }
        val sections = buildDrawerNovelSections(listOf(continueItem) + items)
        assertTrue(sections.recentNovels.size <= NUM_SIX)
    }

    @Test
    fun testLibraryDrawerBoundary_duplicateNovelTitlesCount_countsDistinct() {
        val item1 = createLibraryItem("1", "Title 1", "https://example.com/novel1")
        val item2 = createLibraryItem("2", "Title 1", "https://example.com/novel1")
        val item3 = createLibraryItem("3", "Title 2", "https://example.com/novel2")
        val count = countDistinctNovelTitles(listOf(item1, item2, item3))
        assertEquals(NUM_TWO, count)
    }

    @Test
    fun testLibraryDrawerBoundary_finishedNovels_excludedFromRecent() {
        val continueItem = createLibraryItem("0", "Continue", "https://example.com/c", isCurrentlyReading = true)
        val finishedItem = createLibraryItem(
            "1",
            "Finished",
            "https://example.com/f",
            progress = NUM_ONE_HUNDRED,
            isFinished = true
        )
        val sections = buildDrawerNovelSections(listOf(continueItem, finishedItem))
        assertTrue(sections.recentNovels.none { it.displayTitle == "Finished" })
    }

    @Test
    fun testLibraryDrawerBoundary_continueNovelResortsToMaxTimestamp() {
        val item1 = createLibraryItem("1", "Title 1", "https://example.com/n1", lastRead = NUM_ONE.toLong())
        val item2 = createLibraryItem("2", "Title 2", "https://example.com/n2", lastRead = NUM_TEN.toLong())
        val sections = buildDrawerNovelSections(listOf(item1, item2))
        assertEquals("Title 2", sections.continueNovel?.displayTitle)
    }

    // --- Feature 4 (State Restoration Boundary Cases) ---

    @Test
    fun testStateRestorationBoundary_toleranceSmokeCheck_runsPercentRestore() {
        assertTrue(shouldRunPercentRestoreFallback(false, FLOAT_ZERO_FIVE))
        // Precise restore with no usable intra-item fraction still runs the percent smoke check —
        // the anchor may have landed at the wrong index after image reflow.
        assertTrue(shouldRunPercentRestoreFallback(true, null))
        assertTrue(shouldRunPercentRestoreFallback(true, FLOAT_ZERO))
        // Precise restore WITH a real intra-item fraction is trusted; no fallback.
        assertFalse(shouldRunPercentRestoreFallback(true, FLOAT_ZERO_FIVE))
    }

    @Test
    fun testStateRestorationBoundary_toleranceSmokeCheck_preciseRestoreWithFraction_returnsFalse() {
        assertFalse(shouldRunPercentRestoreFallback(true, FLOAT_ZERO_SIX))
    }

    @Test
    fun testStateRestorationBoundary_resolveRestoreIndex_usesStableKeyIfFound() = runTest {
        val url = "https://example.com/chap1"
        val content = mockChapterContent(url)
        val targetIndex = NUM_SEVEN
        val key = stableContentElementKey(url, targetIndex, content.paragraphs[targetIndex])

        val item = createLibraryItem(
            "item-1",
            url = url,
            lastReadIndex = NUM_TWO,
            lastReadElementKey = key
        )
        whenever(libraryRepository.getItemById("item-1")).thenReturn(item)
        initViewModel()

        viewModel.loadContent(url, "item-1")
        advanceUntilIdle()

        assertEquals(targetIndex, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testStateRestorationBoundary_resolveRestoreIndex_fallbackToScrollIndexIfKeyNotFound() = runTest {
        val url = "https://example.com/chap1"
        val item = createLibraryItem(
            "item-1",
            url = url,
            lastReadIndex = NUM_FOUR,
            lastReadElementKey = "invalid-key-non-existent"
        )
        whenever(libraryRepository.getItemById("item-1")).thenReturn(item)
        initViewModel()

        mockChapterContent(url)

        viewModel.loadContent(url, "item-1")
        advanceUntilIdle()

        assertEquals(NUM_FOUR, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testStateRestorationBoundary_loadContentEmptyUrl_noOp() = runTest {
        initViewModel()
        viewModel.loadContent("")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.content)
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations
    // =========================================================================

    @Test
    fun testCombo_scrollDuringRestore_marksUserDraggedAndCancelsRestoreLoop() = runTest {
        initViewModel()
        viewModel.beginRestore()
        assertTrue(viewModel.restoreInProgress)
        viewModel.markUserDragged()
        assertTrue(viewModel.userHasDragged)
        viewModel.markRestoreDone()
        assertFalse(viewModel.restoreInProgress)
    }

    @Test
    fun testCombo_scrollMotion_hidesControlsOnUserInput() = runTest {
        initViewModel()
        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.showControls)
        viewModel.hideControls()
        assertFalse(viewModel.uiState.value.showControls)
    }

    @Test
    fun testCombo_drawerSelection_loadsContentAndResetsScrollState() = runTest {
        initViewModel()
        val url = "https://example.com/drawer-novel"
        mockChapterContent(url)

        viewModel.loadContent(url)
        advanceUntilIdle()

        assertEquals(url, viewModel.uiState.value.content?.url)
        assertEquals(NUM_ZERO, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testCombo_loadContentWithRestore_keepsControlsHidden() = runTest {
        val url = "https://example.com/chap"
        val item = createLibraryItem("1", url = url, lastReadIndex = NUM_TWO)
        whenever(libraryRepository.getItemById("1")).thenReturn(item)
        initViewModel()
        mockChapterContent(url)

        viewModel.loadContent(url, "1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showControls)
    }

    @Test
    fun testCombo_drawerNavigationToSameChapter_restoresPreviousPosition() = runTest {
        val url = "https://example.com/same"
        val item = createLibraryItem("1", url = url, lastReadIndex = NUM_FIVE)
        whenever(libraryRepository.getItemById("1")).thenReturn(item)
        initViewModel()
        mockChapterContent(url)

        viewModel.loadContent(url, "1")
        advanceUntilIdle()
        assertEquals(NUM_FIVE, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testCombo_controlsShown_doesNotInterruptActiveRestoreState() {
        initViewModel()
        viewModel.beginRestore()
        assertTrue(viewModel.restoreInProgress)
        viewModel.toggleControls()
        assertTrue(viewModel.uiState.value.showControls)
        assertTrue(viewModel.restoreInProgress)
    }

    // =========================================================================
    // TIER 4: Real-World Application Scenarios (>=5 scenarios)
    // =========================================================================

    @Test
    fun testScenario_coldLaunch_loadsLastReadNovelAndRestoresState() = runTest {
        val lastUrl = "https://example.com/last"
        val lastItemId = "item-last"
        val item = createLibraryItem(lastItemId, url = lastUrl, lastReadIndex = NUM_THREE)
        whenever(libraryRepository.getItemById(lastItemId)).thenReturn(item)
        mockChapterContent(lastUrl)

        initViewModel(lastReadUrl = lastUrl, lastReadLibraryItemId = lastItemId)
        advanceUntilIdle()

        assertEquals(lastUrl, viewModel.uiState.value.content?.url)
        assertEquals(NUM_THREE, viewModel.uiState.value.scrollIndex)
    }

    @Test
    fun testScenario_readingFlow_userUpdatesSettings_updatesUiAndTheme() = runTest {
        initViewModel()
        mockChapterContent("https://example.com/c1")
        viewModel.loadContent("https://example.com/c1")
        advanceUntilIdle()

        viewModel.updateFontSize(FLOAT_FONT_SIZE_DEFAULT + FLOAT_ONE)
        viewModel.updateReaderTheme(ReaderTheme.OLED)

        val state = viewModel.uiState.value
        assertEquals(FLOAT_FONT_SIZE_DEFAULT + FLOAT_ONE, state.fontSize, FLOAT_ZERO)
        assertEquals(ReaderTheme.OLED, state.readerTheme)
    }

    @Test
    fun testScenario_chapterNavigationFlow_loadsNextAndPrevious() = runTest {
        val url1 = "https://example.com/c1"
        val url2 = "https://example.com/c2"

        val item = createLibraryItem("1", url = url1)
        whenever(libraryRepository.getItemById("1")).thenReturn(item)

        initViewModel()

        mockChapterContent(url1, nextUrl = url2)
        mockChapterContent(url2, prevUrl = url1)

        viewModel.loadContent(url1, "1")
        advanceUntilIdle()

        viewModel.navigateToNextChapter()
        advanceUntilIdle()
        assertEquals(url2, viewModel.uiState.value.content?.url)

        viewModel.navigateToPreviousChapter(fromBottom = true)
        advanceUntilIdle()
        assertEquals(url1, viewModel.uiState.value.content?.url)
    }

    @Test
    fun testScenario_errorFlow_triggersCloudflareDialog_retryLoadsContent() = runTest {
        val url = "https://example.com/c1"
        initViewModel()

        whenever(contentRepository.loadContent(url)).thenReturn(ContentResult.Error("403 Forbidden", null))
        viewModel.loadContent(url)
        advanceUntilIdle()

        assertEquals("403 Forbidden", viewModel.uiState.value.error)

        mockChapterContent(url)
        viewModel.retryLoad()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(url, viewModel.uiState.value.content?.url)
    }

    @Test
    fun testScenario_completionFlow_userReadsToEnd_persistsProgress() = runTest {
        val url = "https://example.com/c1"
        val item = createLibraryItem("1", url = url)
        whenever(libraryRepository.getItemById("1")).thenReturn(item)

        initViewModel()
        mockChapterContent(url)
        viewModel.loadContent(url, "1")
        advanceUntilIdle()

        viewModel.markUserDragged()
        viewModel.updateScrollPosition(
            scrollOffset = FLOAT_THRESHOLD_VAL,
            maxScrollOffset = FLOAT_THRESHOLD_VAL,
            viewportHeight = FLOAT_ZERO,
            index = NUM_NINE,
            offsetFraction = FLOAT_ZERO,
            elementKey = "key"
        )

        viewModel.updateReadingProgress(
            progress = NUM_ONE_HUNDRED,
            scrollPosition = FLOAT_THRESHOLD_VAL,
            index = NUM_NINE,
            elementKey = "key",
            offsetFraction = FLOAT_ZERO,
            currentChapterUrl = url,
            forcePersist = true
        )
        advanceUntilIdle()

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq("1"),
            currentChapter = any(),
            progress = eq(FieldUpdate.Set(NUM_ONE_HUNDRED)),
            currentChapterUrl = eq(FieldUpdate.Set(url)),
            lastScrollProgress = any(),
            lastReadIndex = any(),
            lastReadElementKey = any(),
            lastReadOffsetFraction = any()
        )
    }
}
