package io.aatricks.novelscraper.ui.viewmodel

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.timeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private lateinit var testDispatcher: TestDispatcher

    private val libraryRepository: LibraryRepository = mock()
    private val contentRepository: ContentRepository = mock()
    private val exploreRepository: ExploreRepository = mock()

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(testDispatcher)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(libraryRepository.loadCollapsedSources()).thenReturn(emptySet())
        whenever(libraryRepository.getGroupedByTitle(anyOrNull())).thenReturn(emptyMap())
        whenever(libraryRepository.getGroupedBySourceAndTitle(anyOrNull())).thenReturn(emptyMap())
        runTest {
            whenever(libraryRepository.clearUpdateIndicator(any())).thenReturn(false)
            whenever(libraryRepository.updateItem(any())).thenReturn(true)
        }

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
    fun `selection does not survive a new ViewModel instance`() = runTest {
        val itemId = "id-1"
        val libraryItems = MutableStateFlow(
            listOf(
                LibraryItem(id = itemId, title = "Novel", url = "https://example.com/novel")
            )
        )
        whenever(libraryRepository.libraryItems).thenReturn(libraryItems)

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository)
        advanceUntilIdle()

        activeViewModel.toggleSelection(itemId)
        advanceUntilIdle()

        assertEquals(setOf(itemId), activeViewModel.uiState.value.selectedIds)

        val restoredViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository)
        advanceUntilIdle()

        assertTrue(restoredViewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(restoredViewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `removeSelectedItems deletes current transient selection`() = runTest {
        val item1 = LibraryItem(id = "id-1", title = "Novel 1", url = "https://example.com/novel-1")
        val item2 = LibraryItem(id = "id-2", title = "Novel 2", url = "https://example.com/novel-2")
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item1, item2)))

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository)
        advanceUntilIdle()

        activeViewModel.selectItem(item1.id)
        activeViewModel.selectItem(item2.id)
        advanceUntilIdle()

        activeViewModel.removeSelectedItems()
        advanceUntilIdle()

        verify(contentRepository).clearCachesForUrls(listOf(item1.url, item2.url))
        verify(libraryRepository).removeItems(setOf(item1.id, item2.id))
        assertTrue(activeViewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(activeViewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `enter selection mode keeps selection affordance visible before choosing items`() = runTest {
        advanceUntilIdle()

        viewModel.enterSelectionMode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())

        viewModel.clearSelection()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
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

        verify(libraryRepository, atLeastOnce()).saveCollapsedSources(any())
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
        val insertedTitle = argumentCaptor<String>()
        val insertedUrl = argumentCaptor<String>()
        val insertedContentType = argumentCaptor<ContentType>()
        val insertedCurrentChapter = argumentCaptor<String>()
        val insertedBaseTitle = argumentCaptor<String>()
        val insertedBaseNovelUrl = argumentCaptor<String>()
        val insertedSourceName = argumentCaptor<String>()
        val insertedTotalChapters = argumentCaptor<Int>()
        val createdItem = LibraryItem(
            id = "new-id",
            title = "Chapter 10",
            url = latestUrl,
            currentChapter = "Chapter 10",
            baseTitle = baseTitle,
            baseNovelUrl = baseNovelUrl,
            sourceName = sourceName,
            totalChapters = details.chapters.size
        )

        whenever(exploreRepository.getNovelDetails(baseNovelUrl, sourceName)).thenReturn(details)
        whenever(libraryRepository.getItemByUrl(latestUrl)).thenReturn(null)
        whenever(
            libraryRepository.addItem(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(createdItem)

        viewModel.openNewChapter(baseTitle, baseNovelUrl, sourceName) { url, id ->
            loadedUrl = url
            loadedId = id
        }
        advanceUntilIdle()

        verify(libraryRepository, timeout(1000)).addItem(
            insertedTitle.capture(),
            insertedUrl.capture(),
            insertedContentType.capture(),
            insertedCurrentChapter.capture(),
            insertedBaseTitle.capture(),
            insertedBaseNovelUrl.capture(),
            insertedSourceName.capture(),
            insertedTotalChapters.capture()
        )
        assertEquals("Chapter 10", insertedTitle.firstValue)
        assertEquals(latestUrl, insertedUrl.firstValue)
        assertEquals(ContentType.WEB, insertedContentType.firstValue)
        assertEquals("Chapter 10", insertedCurrentChapter.firstValue)
        assertEquals(baseTitle, insertedBaseTitle.firstValue)
        assertEquals(baseNovelUrl, insertedBaseNovelUrl.firstValue)
        assertEquals(sourceName, insertedSourceName.firstValue)
        assertEquals(details.chapters.size, insertedTotalChapters.firstValue.toInt())
        verify(contentRepository, never()).prefetch(any(), any())
        assertEquals(latestUrl, loadedUrl)
        assertEquals(createdItem.id, loadedId)
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
        whenever(libraryRepository.getItemByUrl(latestUrl)).thenReturn(existingItem)

        viewModel.openNewChapter(baseTitle, baseNovelUrl, sourceName) { url, id ->
            loadedUrl = url
            loadedId = id
        }
        advanceUntilIdle()

        verify(libraryRepository, timeout(1000)).updateItem(check<LibraryItem> {
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

        whenever(libraryRepository.getItemByUrl(chapter.url)).thenReturn(null)
        whenever(
            libraryRepository.addItem(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(
            LibraryItem(
                id = "chapter-11-id",
                title = chapter.title,
                url = chapter.url,
                currentChapter = "Chapter 11",
                baseTitle = "Novel",
                baseNovelUrl = "https://example.com/novel",
                sourceName = "Source1"
            )
        )
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
