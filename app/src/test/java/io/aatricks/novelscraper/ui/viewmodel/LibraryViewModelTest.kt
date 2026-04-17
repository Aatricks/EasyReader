package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.PrefetchMode
import io.aatricks.novelscraper.data.model.PrefetchResult
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.custom.CustomSourceRepository
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
    private val customSourceRepository: CustomSourceRepository = mock()

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
            exploreRepository,
            customSourceRepository
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
        val vm = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, customSourceRepository)
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
                any(),
                anyOrNull()
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
            insertedTotalChapters.capture(),
            anyOrNull()
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
    fun `beginAiSetup stores preview and confirmAiSetup saves recipe-backed item`() = runTest {
        val url = "https://example.com/unsupported"
        val preview = io.aatricks.novelscraper.data.model.AiSourceSetupPreview(
            displayName = "Example Source",
            title = "The Great Story",
            baseNovelUrl = "https://example.com/series/the-great-story",
            firstChapterUrl = "https://example.com/series/the-great-story/chapter-1",
            firstChapterTitle = "Chapter 1",
            chapterCount = 12,
            contentKind = io.aatricks.novelscraper.data.model.CustomSourceContentKind.NOVEL,
            recipe = io.aatricks.novelscraper.data.model.CustomSourceRecipeDefinition(
                displayName = "Example Source",
                baseNovelUrl = "https://example.com/series/the-great-story",
                contentKind = io.aatricks.novelscraper.data.model.CustomSourceContentKind.NOVEL,
                titleSelector = ".series-title",
                chapterItemSelector = ".chapter-list li",
                textContentSelector = ".chapter-content p"
            )
        )
        val savedRecipe = io.aatricks.novelscraper.data.model.CustomSourceRecipe(
            id = "recipe-1",
            displayName = "Example Source",
            baseNovelUrl = preview.baseNovelUrl,
            contentKind = preview.contentKind,
            recipeJson = "{}",
            createdAt = 1L,
            updatedAt = 1L,
            lastValidatedAt = 1L
        )
        doReturn(Result.success(preview)).whenever(customSourceRepository).generateSetupPreview(url)
        doReturn(savedRecipe).whenever(customSourceRepository).saveRecipe(preview)
        whenever(libraryRepository.getItemByUrl(preview.firstChapterUrl)).thenReturn(null)
        whenever(
            libraryRepository.addItem(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyOrNull()
            )
        ).thenReturn(
            LibraryItem(
                id = "item-1",
                title = "The Great Story - Chapter 1",
                url = preview.firstChapterUrl,
                baseTitle = preview.title,
                baseNovelUrl = preview.baseNovelUrl,
                sourceName = preview.displayName,
                customRecipeId = savedRecipe.id
            )
        )

        viewModel.beginAiSetup(url)
        advanceUntilIdle()

        assertEquals(preview, viewModel.uiState.value.aiSetupPreview)

        viewModel.confirmAiSetup()
        advanceUntilIdle()

        verify(libraryRepository).addItem(
            any(),
            eq(preview.firstChapterUrl),
            eq(ContentType.WEB),
            any(),
            eq(preview.title),
            eq(preview.baseNovelUrl),
            eq(preview.displayName),
            eq(preview.chapterCount),
            eq(savedRecipe.id)
        )
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
                any(),
                anyOrNull()
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
