package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.work.ChapterDownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
    private lateinit var reconciler: DownloadStatusReconciler

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        LibraryViewModel.isUnderTest = true
        LibraryViewModel.defaultDispatcher = testDispatcher

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(libraryRepository.loadCollapsedSources()).thenReturn(emptySet())
        whenever(libraryRepository.getGroupedByTitle(anyOrNull())).thenReturn(emptyMap())
        whenever(libraryRepository.getGroupedBySourceAndTitle(anyOrNull())).thenReturn(emptyMap())
        runTest {
            whenever(libraryRepository.clearUpdateIndicator(any())).thenReturn(false)
            whenever(libraryRepository.updateItem(any())).thenReturn(true)
        }

        reconciler = DownloadStatusReconciler(libraryRepository)

        viewModel = LibraryViewModel(
            libraryRepository,
            contentRepository,
            exploreRepository,
            io.aatricks.easyreader.work.NoOpChapterDownloadQueue(),
            reconciler
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LibraryViewModel.isUnderTest = false
        LibraryViewModel.defaultDispatcher = Dispatchers.Default
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

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        advanceUntilIdle()

        activeViewModel.toggleSelection(itemId)
        advanceUntilIdle()

        assertEquals(setOf(itemId), activeViewModel.uiState.value.selectedIds)

        val restoredViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        advanceUntilIdle()

        assertTrue(restoredViewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(restoredViewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `removeSelectedItems deletes current transient selection`() = runTest {
        val item1 = LibraryItem(id = "id-1", title = "Novel 1", url = "https://example.com/novel-1")
        val item2 = LibraryItem(id = "id-2", title = "Novel 2", url = "https://example.com/novel-2")
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item1, item2)))

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        advanceUntilIdle()

        activeViewModel.selectItem(item1.id)
        activeViewModel.selectItem(item2.id)
        advanceUntilIdle()

        activeViewModel.removeSelectedItems()
        advanceUntilIdle()

        verify(contentRepository).clearCachesAndDownloadsForUrls(listOf(item1.url, item2.url))
        verify(libraryRepository).removeItems(setOf(item1.id, item2.id))
        assertTrue(activeViewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(activeViewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `removeItemsImmediate clears downloads and removes items without undo window`() = runTest {
        val item1 = LibraryItem(id = "id-1", title = "Novel 1", url = "https://example.com/novel-1")
        val item2 = LibraryItem(id = "id-2", title = "Novel 2", url = "https://example.com/novel-2")
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item1, item2)))

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        advanceUntilIdle()

        activeViewModel.removeItemsImmediate(setOf(item1.id, item2.id))
        advanceUntilIdle()

        verify(contentRepository).clearCachesAndDownloadsForUrls(listOf(item1.url, item2.url))
        verify(libraryRepository).removeItems(setOf(item1.id, item2.id))
        // The immediate path must NOT populate the pending-deletion undo state.
        assertTrue(activeViewModel.pendingDeletion.value.isEmpty())
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
        val vm = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
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
    fun `addChapters queues worker download and updates optimistic cache state`() = runTest {
        val chapter = ChapterInfo("Chapter 11", "https://example.com/novel/chapter-11")
        val queue = RecordingChapterDownloadQueue()
        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, queue, reconciler)

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

        activeViewModel.addChapters(
            chapters = listOf(chapter),
            baseTitle = "Novel",
            baseNovelUrl = "https://example.com/novel",
            sourceName = "Source1"
        )
        advanceUntilIdle()

        assertEquals(listOf(RecordedEnqueue(chapter.url, replaceExisting = false)), queue.enqueued)
        verify(contentRepository, never()).prefetchWithProgress(any(), any(), any())
        verify(libraryRepository, never()).markDownloaded("chapter-11-id", true)
        val state = activeViewModel.uiState.value.chapterCacheStates[chapter.url]
        assertNotNull(state)
        assertTrue(state!!.isInProgress)
        assertTrue(state.isPersistentDownload)
        assertFalse(state.isComplete)
    }

    @Test
    fun `addChapters queues existing non-downloaded chapter without adding duplicate item`() = runTest {
        val chapter = ChapterInfo("Chapter 12", "https://example.com/novel/chapter-12")
        val existingItem = LibraryItem(
            id = "chapter-12-id",
            title = chapter.title,
            url = chapter.url,
            currentChapter = "Chapter 12",
            baseTitle = "Novel",
            baseNovelUrl = "https://example.com/novel",
            sourceName = "Source1",
            isDownloaded = false
        )
        val queue = RecordingChapterDownloadQueue()
        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, queue, reconciler)

        whenever(libraryRepository.getItemByUrl(chapter.url)).thenReturn(existingItem)

        activeViewModel.addChapters(
            chapters = listOf(chapter),
            baseTitle = "Novel",
            baseNovelUrl = "https://example.com/novel",
            sourceName = "Source1"
        )
        advanceUntilIdle()

        verify(libraryRepository, never()).addItem(any(), any(), any(), any(), any(), any(), any(), any())
        assertEquals(listOf(RecordedEnqueue(chapter.url, replaceExisting = false)), queue.enqueued)
        verify(contentRepository, never()).prefetchWithProgress(any(), any(), any())
        verify(libraryRepository, never()).markDownloaded(existingItem.id, true)
        val state = activeViewModel.uiState.value.chapterCacheStates[chapter.url]
        assertNotNull(state)
        assertTrue(state!!.isInProgress)
        assertTrue(state.isPersistentDownload)
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

    @Test
    fun `refreshChapterCacheStates demotes downloaded flag when persistent inspect is incomplete`() = runTest {
        val chapterUrl = "https://example.com/novel/chapter-13"
        val downloadedItem = LibraryItem(
            id = "chapter-13-id",
            title = "Chapter 13",
            url = chapterUrl,
            currentChapter = "Chapter 13",
            baseTitle = "Novel",
            isDownloaded = true
        )
        val libraryItems = MutableStateFlow(listOf(downloadedItem))
        // No permanent failures recorded, but two images are simply missing from disk.
        // The new reconciler treats this as an authoritative terminal signal that the
        // chapter is not fully downloaded and demotes the flag so the chapter list
        // reflects reality on next render.
        val inspected = PrefetchResult(
            url = chapterUrl,
            htmlCached = true,
            totalImages = 4,
            cachedImages = 2,
            isComplete = false,
            isPersistentDownload = true
        )

        whenever(libraryRepository.libraryItems).thenReturn(libraryItems)
        whenever(libraryRepository.getGroupedByTitle(anyOrNull())).thenReturn(mapOf("Novel" to listOf(downloadedItem)))
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(inspected)

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        activeViewModel.refreshChapterCacheStates(listOf(chapterUrl))
        advanceUntilIdle()

        verify(contentRepository, timeout(1000)).inspectDownload(chapterUrl)
        verify(contentRepository, never()).inspectCache(chapterUrl)
        verify(libraryRepository, timeout(1000)).markDownloaded(downloadedItem.id, false)
        assertEquals(inspected, activeViewModel.uiState.value.chapterCacheStates[chapterUrl])
    }

    @Test
    fun `refreshChapterCacheStates does not demote when persistent inspect is still in progress`() = runTest {
        val chapterUrl = "https://example.com/novel/chapter-13b"
        val downloadedItem = LibraryItem(
            id = "chapter-13b-id",
            title = "Chapter 13b",
            url = chapterUrl,
            currentChapter = "Chapter 13b",
            baseTitle = "Novel",
            isDownloaded = true
        )
        val libraryItems = MutableStateFlow(listOf(downloadedItem))
        val inProgress = PrefetchResult(
            url = chapterUrl,
            htmlCached = true,
            totalImages = 4,
            cachedImages = 2,
            isComplete = false,
            isInProgress = true,
            isPersistentDownload = true
        )

        whenever(libraryRepository.libraryItems).thenReturn(libraryItems)
        whenever(libraryRepository.getGroupedByTitle(anyOrNull())).thenReturn(mapOf("Novel" to listOf(downloadedItem)))
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(inProgress)

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        activeViewModel.refreshChapterCacheStates(listOf(chapterUrl))
        advanceUntilIdle()

        verify(libraryRepository, never()).markDownloaded(eq(downloadedItem.id), eq(false))
    }

    @Test
    fun `addChapters never marks download before worker completion`() = runTest {
        val chapter = ChapterInfo("Chapter 14", "https://example.com/novel/chapter-14")
        val queue = RecordingChapterDownloadQueue()
        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, queue, reconciler)

        whenever(libraryRepository.getItemByUrl(chapter.url)).thenReturn(null)
        whenever(
            libraryRepository.addItem(any(), any(), any(), any(), any(), any(), any(), any())
        ).thenReturn(
            LibraryItem(
                id = "chapter-14-id",
                title = chapter.title,
                url = chapter.url,
                currentChapter = "Chapter 14",
                baseTitle = "Novel",
                baseNovelUrl = "https://example.com/novel",
                sourceName = "Source1"
            )
        )

        activeViewModel.addChapters(
            chapters = listOf(chapter),
            baseTitle = "Novel",
            baseNovelUrl = "https://example.com/novel",
            sourceName = "Source1"
        )
        advanceUntilIdle()

        assertEquals(listOf(RecordedEnqueue(chapter.url, replaceExisting = false)), queue.enqueued)
        verify(libraryRepository, never()).markDownloaded(eq("chapter-14-id"), eq(true))
        verify(contentRepository, never()).prefetchWithProgress(any(), any(), any())
    }

    @Test
    fun `retryDownload clears permanent failures before replacing queued work`() = runTest {
        val url = "https://example.com/novel/chapter-retry"
        val queue = RecordingChapterDownloadQueue()
        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, queue, reconciler)

        activeViewModel.retryDownload(url)
        advanceUntilIdle()

        verify(contentRepository).clearPermanentFailures(url)
        assertEquals(listOf(RecordedEnqueue(url, replaceExisting = true)), queue.enqueued)
        verify(contentRepository, never()).prefetchWithProgress(any(), any(), any())
        val state = activeViewModel.uiState.value.chapterCacheStates[url]
        assertNotNull(state)
        assertTrue(state!!.isInProgress)
        assertTrue(state.isPersistentDownload)
    }

    @Test
    fun `refreshChapterCacheStates demotes downloaded flag when permanent failures show up`() = runTest {
        val chapterUrl = "https://example.com/novel/chapter-15"
        val downloadedItem = LibraryItem(
            id = "chapter-15-id",
            title = "Chapter 15",
            url = chapterUrl,
            currentChapter = "Chapter 15",
            baseTitle = "Novel",
            isDownloaded = true
        )
        val libraryItems = MutableStateFlow(listOf(downloadedItem))
        val inspected = PrefetchResult(
            url = chapterUrl,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 3,
            isComplete = true,
            isPersistentDownload = true,
            hasPermanentFailures = true
        )

        whenever(libraryRepository.libraryItems).thenReturn(libraryItems)
        whenever(libraryRepository.getGroupedByTitle(anyOrNull())).thenReturn(mapOf("Novel" to listOf(downloadedItem)))
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(inspected)

        val activeViewModel = LibraryViewModel(libraryRepository, contentRepository, exploreRepository, io.aatricks.easyreader.work.NoOpChapterDownloadQueue(), reconciler)
        activeViewModel.refreshChapterCacheStates(listOf(chapterUrl))
        advanceUntilIdle()

        verify(libraryRepository, timeout(1000)).markDownloaded(downloadedItem.id, false)
        assertEquals(inspected, activeViewModel.uiState.value.chapterCacheStates[chapterUrl])
    }

    @Test
    fun `removeDownload cancels work clears disk and refreshes cache state`() = runTest {
        val itemId = "id-1"
        val url = "https://example.com/novel-1"
        val item = LibraryItem(id = itemId, title = "Novel 1", url = url)
        
        whenever(libraryRepository.getItemById(itemId)).thenReturn(item)
        val queue: ChapterDownloadQueue = mock()
        whenever(queue.observeAll()).thenReturn(MutableStateFlow(emptyMap()))
        
        val activeViewModel = LibraryViewModel(
            libraryRepository,
            contentRepository,
            exploreRepository,
            queue,
            reconciler
        )
        advanceUntilIdle()
        
        val inspected = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false
        )
        whenever(contentRepository.inspectCache(url)).thenReturn(inspected)
        whenever(libraryRepository.markDownloaded(itemId, false)).thenReturn(true)
        
        activeViewModel.removeDownload(itemId)
        advanceUntilIdle()
        
        verify(queue).cancel(url)
        verify(contentRepository).clearDownload(url)
        verify(libraryRepository).markDownloaded(itemId, false)
        
        assertEquals(inspected, activeViewModel.uiState.value.chapterCacheStates[url])
    }

    @Test
    fun `committed deletion cancels queued downloads and drops cache states`() = runTest {
        val itemId = "id-1"
        val url = "https://example.com/novel-1"
        val item = LibraryItem(id = itemId, title = "Novel 1", url = url)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item)))
        whenever(libraryRepository.removeItems(any())).thenReturn(1)
        
        val queue: ChapterDownloadQueue = mock()
        whenever(queue.observeAll()).thenReturn(MutableStateFlow(emptyMap()))
        
        val activeViewModel = LibraryViewModel(
            libraryRepository,
            contentRepository,
            exploreRepository,
            queue,
            reconciler
        )
        advanceUntilIdle()
        
        val result = PrefetchResult(url = url, htmlCached = true, totalImages = 5, cachedImages = 5, isComplete = true)
        whenever(contentRepository.inspectDownload(url)).thenReturn(result)
        
        activeViewModel.refreshChapterCacheStates(listOf(url))
        advanceUntilIdle()
        
        assertEquals(result, activeViewModel.uiState.value.chapterCacheStates[url])
        
        activeViewModel.removeItem(itemId)
        
        verify(queue, never()).cancel(url)
        assertNotNull(activeViewModel.uiState.value.chapterCacheStates[url])
        
        advanceTimeBy(5001)
        runCurrent()
        
        verify(queue).cancel(url)
        assertNull(activeViewModel.uiState.value.chapterCacheStates[url])
    }

    @Test
    fun `retryDownload surfaces error and rolls back when enqueue fails`() = runTest {
        val url = "https://example.com/novel-1"
        
        val queue: ChapterDownloadQueue = mock()
        whenever(queue.enqueue(eq(url), any())).thenReturn(false)
        whenever(queue.observeAll()).thenReturn(MutableStateFlow(emptyMap()))
        
        val activeViewModel = LibraryViewModel(
            libraryRepository,
            contentRepository,
            exploreRepository,
            queue,
            reconciler
        )
        advanceUntilIdle()
        
        activeViewModel.retryDownload(url)
        advanceUntilIdle()
        
        assertEquals("Failed to queue download", activeViewModel.uiState.value.error)
        
        val state = activeViewModel.uiState.value.chapterCacheStates[url]
        assertTrue(state == null || !state.isInProgress)
    }
}

private data class RecordedEnqueue(val url: String, val replaceExisting: Boolean)

private class RecordingChapterDownloadQueue : ChapterDownloadQueue {
    val enqueued = mutableListOf<RecordedEnqueue>()
    private val results = MutableStateFlow<Map<String, PrefetchResult>>(emptyMap())

    override fun enqueue(url: String, replaceExisting: Boolean): Boolean {
        enqueued += RecordedEnqueue(url, replaceExisting)
        return true
    }

    override fun cancel(url: String) = Unit

    override fun observeChapter(url: String): Flow<PrefetchResult?> =
        results.map { it[url] }

    override fun observeAll(): Flow<Map<String, PrefetchResult>> = results

    override fun prune() = Unit
}
