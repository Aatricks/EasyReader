package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.PrefetchMode
import io.aatricks.novelscraper.data.model.PrefetchResult
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.mockito.Mockito.timeout
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

    @Test
    fun `openNewChapter adds latest chapter when missing`() = runTest {
        val baseTitle = "Novel"
        val baseNovelUrl = "https://example.com/novel"
        val sourceName = "Source1"
        val latestUrl = "https://example.com/novel/chapter-10"
        val details = ExploreItem(
            title = baseTitle,
            url = baseNovelUrl,
            source = sourceName,
            chapters = listOf(
                ChapterInfo("Chapter 9", "https://example.com/novel/chapter-9"),
                ChapterInfo("Chapter 10", latestUrl)
            )
        )
        var loadedUrl: String? = null
        var loadedId: String? = null
        val insertedItem = argumentCaptor<LibraryItem>()

        whenever(exploreRepository.getNovelDetails(baseNovelUrl, sourceName)).thenReturn(details)
        whenever(libraryDao.getItemByUrl(latestUrl)).thenReturn(null)

        viewModel.openNewChapter(baseTitle, baseNovelUrl, sourceName) { url, id ->
            loadedUrl = url
            loadedId = id
        }
        advanceUntilIdle()

        verify(libraryDao, timeout(1000)).insertItem(insertedItem.capture())
        assertEquals(latestUrl, insertedItem.firstValue.url)
        assertEquals(ContentType.WEB, insertedItem.firstValue.contentType)
        assertEquals(baseTitle, insertedItem.firstValue.baseTitle)
        assertEquals(baseNovelUrl, insertedItem.firstValue.baseNovelUrl)
        assertEquals(sourceName, insertedItem.firstValue.sourceName)
        assertEquals(details.chapters.size, insertedItem.firstValue.totalChapters)
        verify(contentRepository, never()).prefetch(any(), any())
        assertEquals(latestUrl, loadedUrl)
        assertEquals(insertedItem.firstValue.id, loadedId)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `openNewChapter reuses existing latest chapter when already in library`() = runTest {
        val baseTitle = "Novel"
        val baseNovelUrl = "https://example.com/novel"
        val sourceName = "Source1"
        val latestUrl = "https://example.com/novel/chapter-10"
        val existingItem = LibraryItem(
            id = "existing-id",
            title = "Chapter 10",
            url = latestUrl,
            currentChapter = "Chapter 10",
            baseTitle = baseTitle,
            baseNovelUrl = baseNovelUrl,
            sourceName = sourceName,
            totalChapters = 1
        )
        val details = ExploreItem(
            title = baseTitle,
            url = baseNovelUrl,
            source = sourceName,
            chapters = listOf(
                ChapterInfo("Chapter 9", "https://example.com/novel/chapter-9"),
                ChapterInfo("Chapter 10", latestUrl)
            )
        )
        var loadedUrl: String? = null
        var loadedId: String? = null

        whenever(exploreRepository.getNovelDetails(baseNovelUrl, sourceName)).thenReturn(details)
        whenever(libraryDao.getItemByUrl(latestUrl)).thenReturn(existingItem)

        viewModel.openNewChapter(baseTitle, baseNovelUrl, sourceName) { url, id ->
            loadedUrl = url
            loadedId = id
        }
        advanceUntilIdle()

        verify(libraryDao, timeout(1000)).insertItem(check<LibraryItem> {
            assertEquals(existingItem.id, it.id)
            assertEquals(details.chapters.size, it.totalChapters)
            assertEquals(latestUrl, it.url)
        })
        verify(contentRepository, never()).prefetch(any(), any())
        assertEquals(latestUrl, loadedUrl)
        assertEquals(existingItem.id, loadedId)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `addChapters uses user requested prefetch and updates cache state`() = runTest {
        val chapter = ChapterInfo("Chapter 11", "https://example.com/novel/chapter-11")
        val prefetchResult = PrefetchResult(
            url = chapter.url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 5,
            isComplete = true
        )

        whenever(libraryDao.getItemByUrl(chapter.url)).thenReturn(null)
        whenever(contentRepository.prefetch(chapter.url, PrefetchMode.USER_REQUESTED)).thenReturn(prefetchResult)

        viewModel.addChapters(
            chapters = listOf(chapter),
            baseTitle = "Novel",
            baseNovelUrl = "https://example.com/novel",
            sourceName = "Source1"
        )
        advanceUntilIdle()

        verify(contentRepository, timeout(1000)).prefetch(chapter.url, PrefetchMode.USER_REQUESTED)
        assertEquals(prefetchResult, viewModel.uiState.value.chapterCacheStates[chapter.url])
    }

    @Test
    fun `refreshChapterCacheStates stores inspected results`() = runTest {
        val chapterUrl = "https://example.com/novel/chapter-12"
        val inspected = PrefetchResult(
            url = chapterUrl,
            htmlCached = true,
            totalImages = 3,
            cachedImages = 2,
            isComplete = false,
            isInProgress = true
        )

        whenever(contentRepository.inspectCache(chapterUrl)).thenReturn(inspected)

        viewModel.refreshChapterCacheStates(listOf(chapterUrl))
        advanceUntilIdle()

        assertEquals(inspected, viewModel.uiState.value.chapterCacheStates[chapterUrl])
    }
}
