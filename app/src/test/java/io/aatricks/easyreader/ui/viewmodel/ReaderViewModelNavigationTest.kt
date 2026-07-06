package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNavigationTest {

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
                    paragraphSpacing = 1.0f,
                    readerTheme = ReaderTheme.DARK.name,
                    accentTheme = io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name
                )
            )
        )

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
        runTest {
            whenever(libraryRepository.markAsCurrentlyReading(any())).thenReturn(true)
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
    fun `loadContent populates fullChapterList from library when source list is empty`() = runTest {
        val baseTitle = "My Manga"
        val ch1Url = "http://example.com/ch1"
        val ch2Url = "http://example.com/ch2"
        val ch3Url = "http://example.com/ch3"

        val libraryChapters = listOf(
            LibraryItem(id = "1", title = "Chapter 1", url = ch1Url, baseTitle = baseTitle, currentChapter = "Chapter 1"),
            LibraryItem(id = "2", title = "Chapter 2", url = ch2Url, baseTitle = baseTitle, currentChapter = "Chapter 2"),
            LibraryItem(id = "3", title = "Chapter 3", url = ch3Url, baseTitle = baseTitle, currentChapter = "Chapter 3")
        )

        // Mock LibraryRepository behavior
        whenever(libraryRepository.getItemByUrl(ch2Url)).thenReturn(libraryChapters[1])
        whenever(libraryRepository.getItemById(any())).thenAnswer { inv ->
            val id = inv.arguments[0] as String
            libraryChapters.find { it.id == id }
        }
        whenever(libraryRepository.getChaptersByBaseTitle(baseTitle)).thenReturn(libraryChapters)

        // Mock ContentRepository
        whenever(contentRepository.loadContent(ch2Url)).thenReturn(
            ContentResult.Success(
                elements = listOf(ContentElement.Text("Content")),
                title = "Chapter 2",
                url = ch2Url
            )
        )
        whenever(contentRepository.incrementChapterUrl(ch2Url)).thenReturn("http://example.com/guessed-next")
        whenever(contentRepository.decrementChapterUrl(ch2Url)).thenReturn("http://example.com/guessed-prev")

        // Mock ExploreRepository to return empty (offline or failed)
        whenever(exploreRepository.getNovelDetails(any(), any())).thenReturn(null)

        // Action: Load Chapter 2
        viewModel.loadContent(ch2Url)
        advanceUntilIdle()

        // Verification
        val state = viewModel.uiState.value

        // 1. Verify fullChapterList is populated from library
        assertEquals(3, state.fullChapterList.size)
        assertEquals(ch1Url, state.fullChapterList[0].url)
        assertEquals(ch2Url, state.fullChapterList[1].url)
        assertEquals(ch3Url, state.fullChapterList[2].url)

        // 2. Verify navigation URLs are NOT overridden by the incomplete library list (to avoid skipping chapters)
        // Previous of Ch 2 should be the guessed prev
        assertEquals("http://example.com/guessed-prev", state.content?.previousChapterUrl)
        // Next of Ch 2 should be the guessed next
        assertEquals("http://example.com/guessed-next", state.content?.nextChapterUrl)

        // Verify canNavigate flags
        assertEquals(true, state.canNavigatePrevious)
        assertEquals(true, state.canNavigateNext)
    }

    @Test
    fun `navigateToNextChapter loads missing chapter once`() = runTest {
        val currentUrl = "http://example.com/ch1"
        val nextUrl = "http://example.com/ch2"
        val afterNextUrl = "http://example.com/ch3"
        val currentItem = LibraryItem(
            id = "current-id",
            title = "Chapter 1",
            url = currentUrl,
            currentChapter = "Chapter 1",
            baseTitle = "My Manga",
            baseNovelUrl = "http://example.com/series",
            sourceName = "Source"
        )
        val nextItem = currentItem.copy(
            id = "next-id",
            title = "Chapter 2",
            url = nextUrl,
            currentChapter = "Chapter 2"
        )

        whenever(contentRepository.loadContent(currentUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Current")), "Chapter 1", currentUrl)
        )
        whenever(contentRepository.loadContent(nextUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Next")), "Chapter 2", nextUrl)
        )
        whenever(contentRepository.incrementChapterUrl(currentUrl)).thenReturn(nextUrl)
        whenever(contentRepository.decrementChapterUrl(currentUrl)).thenReturn(null)
        whenever(contentRepository.incrementChapterUrl(nextUrl)).thenReturn(afterNextUrl)
        whenever(contentRepository.decrementChapterUrl(nextUrl)).thenReturn(currentUrl)
        whenever(libraryRepository.getItemByUrl(currentUrl)).thenReturn(currentItem)
        whenever(libraryRepository.getItemByUrl(nextUrl)).thenReturn(null, nextItem, nextItem)
        whenever(libraryRepository.getItemById(currentItem.id)).thenReturn(currentItem)
        whenever(libraryRepository.getItemById(nextItem.id)).thenReturn(nextItem)
        whenever(libraryRepository.getChaptersByBaseTitle(currentItem.baseTitle)).thenReturn(listOf(currentItem, nextItem))
        whenever(libraryRepository.addItem(any(), eq(nextUrl), eq(ContentType.WEB), any(), any(), any(), any(), any()))
            .thenReturn(nextItem)
        whenever(exploreRepository.getNovelDetails(any(), any())).thenReturn(null)

        viewModel.loadContent(currentUrl, currentItem.id)
        advanceUntilIdle()

        viewModel.navigateToNextChapter()
        advanceUntilIdle()

        verify(contentRepository, times(1)).loadContent(nextUrl)
        assertEquals(nextUrl, viewModel.uiState.value.content?.url)
    }

    @Test
    fun `navigateToChapter loads missing chapter once`() = runTest {
        val currentUrl = "http://example.com/ch10"
        val targetUrl = "http://example.com/ch12"
        val nextUrl = "http://example.com/ch13"
        val currentItem = LibraryItem(
            id = "current-id",
            title = "Chapter 10",
            url = currentUrl,
            currentChapter = "Chapter 10",
            baseTitle = "My Manga",
            baseNovelUrl = "http://example.com/series",
            sourceName = "Source"
        )
        val targetItem = currentItem.copy(
            id = "target-id",
            title = "Chapter 12",
            url = targetUrl,
            currentChapter = "Chapter 12"
        )

        whenever(contentRepository.loadContent(currentUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Current")), "Chapter 10", currentUrl)
        )
        whenever(contentRepository.loadContent(targetUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Target")), "Chapter 12", targetUrl)
        )
        whenever(contentRepository.incrementChapterUrl(currentUrl)).thenReturn("http://example.com/ch11")
        whenever(contentRepository.decrementChapterUrl(currentUrl)).thenReturn("http://example.com/ch9")
        whenever(contentRepository.incrementChapterUrl(targetUrl)).thenReturn(nextUrl)
        whenever(contentRepository.decrementChapterUrl(targetUrl)).thenReturn(currentUrl)
        whenever(libraryRepository.getItemByUrl(currentUrl)).thenReturn(currentItem)
        whenever(libraryRepository.getItemByUrl(targetUrl)).thenReturn(null, targetItem, targetItem)
        whenever(libraryRepository.getItemById(currentItem.id)).thenReturn(currentItem)
        whenever(libraryRepository.getItemById(targetItem.id)).thenReturn(targetItem)
        whenever(libraryRepository.getChaptersByBaseTitle(currentItem.baseTitle)).thenReturn(listOf(currentItem, targetItem))
        whenever(libraryRepository.addItem(any(), eq(targetUrl), eq(ContentType.WEB), any(), any(), any(), any(), any()))
            .thenReturn(targetItem)
        whenever(exploreRepository.getNovelDetails(any(), any())).thenReturn(null)

        viewModel.loadContent(currentUrl, currentItem.id)
        advanceUntilIdle()

        viewModel.navigateToChapter(targetUrl, "Chapter 12")
        advanceUntilIdle()

        verify(contentRepository, times(1)).loadContent(targetUrl)
        assertEquals(targetUrl, viewModel.uiState.value.content?.url)
    }

    @Test
    fun `loadContent normalizes duplicate source chapters before exposing fullChapterList`() = runTest {
        val baseUrl = "http://example.com/series"
        val sourceName = "Source"
        val currentUrl = "http://example.com/ch2"
        val currentItem = LibraryItem(
            id = "current-id",
            title = "Chapter 2",
            url = currentUrl,
            currentChapter = "Chapter 2",
            baseTitle = "My Manga",
            baseNovelUrl = baseUrl,
            sourceName = sourceName,
            totalChapters = 1
        )
        val details = ExploreItem(
            title = "My Manga",
            url = baseUrl,
            source = sourceName,
            chapters = listOf(
                ChapterInfo("Chapter 1", "http://example.com/ch1"),
                ChapterInfo("Chapter 2", currentUrl),
                ChapterInfo("Chapter 2 duplicate", currentUrl),
                ChapterInfo("Chapter 3", "http://example.com/ch3")
            )
        )

        whenever(contentRepository.loadContent(currentUrl)).thenReturn(
            ContentResult.Success(listOf(ContentElement.Text("Current")), "Chapter 2", currentUrl)
        )
        whenever(contentRepository.incrementChapterUrl(currentUrl)).thenReturn("http://example.com/guessed-next")
        whenever(contentRepository.decrementChapterUrl(currentUrl)).thenReturn("http://example.com/ch1")
        whenever(libraryRepository.getItemById(currentItem.id)).thenReturn(currentItem)
        whenever(libraryRepository.healChapterMetadata(any(), anyOrNull(), anyOrNull(), any())).thenReturn(true)
        whenever(exploreRepository.getNovelDetails(baseUrl, sourceName)).thenReturn(details)

        viewModel.loadContent(currentUrl, currentItem.id)
        advanceUntilIdle()

        assertEquals(
            listOf("http://example.com/ch1", currentUrl, "http://example.com/ch3"),
            viewModel.uiState.value.fullChapterList.map { it.url }
        )
        // Heal writes only metadata via targeted UPDATEs (never a whole-row replace), so it can't
        // clobber a concurrent progress write. totalChapters goes 1 -> 3; no label heal here.
        verify(libraryRepository).healChapterMetadata(
            eq(currentItem.id),
            eq(3),
            anyOrNull(),
            eq(false)
        )
    }
}
