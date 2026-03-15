package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
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

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))

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
}
