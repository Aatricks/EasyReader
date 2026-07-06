package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.work.ChapterDownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDownloadStatesTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private val libraryRepository: LibraryRepository = mock()
    private val contentRepository: ContentRepository = mock()
    private val downloadStatusReconciler: DownloadStatusReconciler = mock()
    private val fakeQueue = FakeChapterDownloadQueue()

    private lateinit var downloadStates: LibraryDownloadStates

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)

        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(emptyList()))

        downloadStates = LibraryDownloadStates(
            scope = testScope,
            repository = libraryRepository,
            contentRepository = contentRepository,
            downloadStatusReconciler = downloadStatusReconciler,
            downloadQueue = fakeQueue
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `in progress queue emissions are merged into cache states`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter1"
        val result = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 10,
            cachedImages = 2,
            isComplete = false,
            isInProgress = true
        )

        fakeQueue.results.value = mapOf(url to result)
        testScheduler.advanceUntilIdle()

        val stateMap = downloadStates.chapterCacheStates.value
        assertTrue(stateMap.containsKey(url))
        assertTrue(stateMap[url]?.isInProgress == true)
    }

    @Test
    fun `terminal queue transition triggers disk inspect and inspect result wins`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter2"
        val inProgressResult = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 10,
            cachedImages = 2,
            isComplete = false,
            isInProgress = true
        )

        // 1. Emit in-progress state to add to queueInProgressUrls
        fakeQueue.results.value = mapOf(url to inProgressResult)
        testScheduler.advanceUntilIdle()

        // Stub inspectDownload
        val inspectResult = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 10,
            cachedImages = 10,
            isComplete = true,
            isInProgress = false
        )
        whenever(contentRepository.inspectDownload(url)).thenReturn(inspectResult)
        // Also stub libraryRepository lookup for the inspect check
        val libraryItem = LibraryItem(id = "item-id", title = "Chapter 2", url = url, isDownloaded = true)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(libraryItem)))

        // 2. Emit terminal map (isInProgress=false, e.g. SUCCEEDED)
        val terminalResult = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 999, // Bogus totals
            cachedImages = 999,
            isComplete = true,
            isInProgress = false
        )
        fakeQueue.results.value = mapOf(url to terminalResult)
        testScheduler.advanceUntilIdle()

        // Verify disk inspect was called and its result won, not the terminal queue payload
        verify(contentRepository).inspectDownload(url)
        val stateMap = downloadStates.chapterCacheStates.value
        assertEquals(10, stateMap[url]?.totalImages)
        assertEquals(10, stateMap[url]?.cachedImages)
        assertEquals(true, stateMap[url]?.isComplete)
    }

    @Test
    fun `data-less cancelled work info does not overwrite an existing downloaded state`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter3"
        val completeState = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 5,
            isComplete = true,
            isInProgress = false
        )

        // Seed via setCacheState
        downloadStates.setCacheState(completeState)
        testScheduler.advanceUntilIdle()

        // Emit terminal CANCELLED-like payload (isInProgress=false, isComplete=false)
        val cancelledResult = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false,
            isInProgress = false
        )
        fakeQueue.results.value = mapOf(url to cancelledResult)
        testScheduler.advanceUntilIdle()

        // Verify contentRepository inspect was not called and map state is unchanged
        verify(contentRepository, never()).inspectDownload(any())
        val stateMap = downloadStates.chapterCacheStates.value
        assertEquals(completeState, stateMap[url])
    }

    @Test
    fun `stale disk inspect does not overwrite live in progress queue state`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter4"
        val inProgressResult = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 10,
            cachedImages = 1,
            isComplete = false,
            isInProgress = true
        )

        // 1. Put url in queueInProgressUrls via in-progress emission
        fakeQueue.results.value = mapOf(url to inProgressResult)
        testScheduler.advanceUntilIdle()

        // 2. Mock contentRepository inspect to return a terminal (not in-progress) state
        val terminalInspect = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 10,
            cachedImages = 10,
            isComplete = true,
            isInProgress = false
        )
        whenever(contentRepository.inspectDownload(url)).thenReturn(terminalInspect)
        val libraryItem = LibraryItem(id = "item-id", title = "Chapter 4", url = url, isDownloaded = true)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(libraryItem)))

        // 3. Trigger manual refresh
        downloadStates.refreshChapterCacheStates(listOf(url))
        testScheduler.advanceUntilIdle()

        // Verify map still shows in-progress
        val stateMap = downloadStates.chapterCacheStates.value
        assertTrue(stateMap[url]?.isInProgress == true)
        assertFalse(stateMap[url]?.isComplete == true)
    }

    @Test
    fun `enqueue failure rolls back optimistic state`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter5"
        fakeQueue.enqueueResult = false

        // Case A: previous absent
        val successA = downloadStates.markPendingAndEnqueue(url)
        assertFalse(successA)
        assertFalse(downloadStates.chapterCacheStates.value.containsKey(url))

        // Case B: previous existed
        val previousState = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 10,
            cachedImages = 5,
            isComplete = false,
            isInProgress = false
        )
        downloadStates.setCacheState(previousState)
        val successB = downloadStates.markPendingAndEnqueue(url)
        assertFalse(successB)
        assertEquals(previousState, downloadStates.chapterCacheStates.value[url])
    }

    @Test
    fun `autoResume caps attempts per session`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter6"
        val item = LibraryItem(id = "item-id", title = "Chapter 6", url = url, isDownloaded = true)

        whenever(libraryRepository.getAllItemsSnapshot()).thenReturn(listOf(item))
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(item)

        // Inspect returns incomplete, retryable
        val incompleteResult = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false,
            isInProgress = false,
            isRetryable = true,
            hasPermanentFailures = false
        )
        whenever(contentRepository.inspectDownload(url)).thenReturn(incompleteResult)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item)))

        // Reconcile 1
        downloadStates.reconcileDownloadedItemsOnDemand()
        testScheduler.advanceUntilIdle()
        assertEquals(1, fakeQueue.enqueued.count { it == url })

        // Clear the mock/fake queues for next reconcile, but keep queue results incomplete
        // Reconcile 2
        downloadStates.reconcileDownloadedItemsOnDemand()
        testScheduler.advanceUntilIdle()
        assertEquals(2, fakeQueue.enqueued.count { it == url })

        // Reconcile 3 (should be skipped because MAX_AUTO_RESUME_ATTEMPTS is 2)
        downloadStates.reconcileDownloadedItemsOnDemand()
        testScheduler.advanceUntilIdle()
        assertEquals(2, fakeQueue.enqueued.count { it == url })
    }

    @Test
    fun `autoResume skips chapters deleted after snapshot`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter7"
        val item = LibraryItem(id = "item-id", title = "Chapter 7", url = url, isDownloaded = true)

        whenever(libraryRepository.getAllItemsSnapshot()).thenReturn(listOf(item))
        // fresh lookup returns null
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(null)

        val incompleteResult = PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false,
            isInProgress = false,
            isRetryable = true,
            hasPermanentFailures = false
        )
        whenever(contentRepository.inspectDownload(url)).thenReturn(incompleteResult)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item)))

        downloadStates.reconcileDownloadedItemsOnDemand()
        testScheduler.advanceUntilIdle()

        assertEquals(0, fakeQueue.enqueued.count { it == url })
    }

    @Test
    fun `autoResume skips terminal permanent failure results`() = runTest(testDispatcher) {
        val url = "https://example.com/chapter8"
        val item = LibraryItem(id = "item-id", title = "Chapter 8", url = url, isDownloaded = true)

        whenever(libraryRepository.getAllItemsSnapshot()).thenReturn(listOf(item))
        whenever(libraryRepository.getItemByUrl(url)).thenReturn(item)

        // terminal permanent failure
        val permFailureResult = PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = 10,
            cachedImages = 5,
            isComplete = true,
            isInProgress = false,
            isRetryable = false,
            hasPermanentFailures = true
        )
        whenever(contentRepository.inspectDownload(url)).thenReturn(permFailureResult)
        whenever(libraryRepository.libraryItems).thenReturn(MutableStateFlow(listOf(item)))

        downloadStates.reconcileDownloadedItemsOnDemand()
        testScheduler.advanceUntilIdle()

        assertEquals(0, fakeQueue.enqueued.count { it == url })
    }

    @Test
    fun `removeCacheStates drops entries`() = runTest(testDispatcher) {
        val url1 = "https://example.com/chapter9a"
        val url2 = "https://example.com/chapter9b"
        val res1 = PrefetchResult(url = url1, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true)
        val res2 = PrefetchResult(url = url2, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true)

        downloadStates.setCacheState(res1)
        downloadStates.setCacheState(res2)
        testScheduler.advanceUntilIdle()

        assertTrue(downloadStates.chapterCacheStates.value.containsKey(url1))
        assertTrue(downloadStates.chapterCacheStates.value.containsKey(url2))

        downloadStates.removeCacheStates(listOf(url1))
        testScheduler.advanceUntilIdle()

        assertFalse(downloadStates.chapterCacheStates.value.containsKey(url1))
        assertTrue(downloadStates.chapterCacheStates.value.containsKey(url2))
    }
}

private class FakeChapterDownloadQueue : ChapterDownloadQueue {
    val results = MutableStateFlow<Map<String, PrefetchResult>>(emptyMap())
    val enqueued = mutableListOf<String>()
    var enqueueResult: Boolean = true
    val cancelled = mutableListOf<String>()
    var pruneCalled = false

    override fun enqueue(url: String, replaceExisting: Boolean): Boolean {
        enqueued.add(url)
        return enqueueResult
    }

    override fun cancel(url: String) {
        cancelled.add(url)
    }

    override fun cancelAll() {
    }

    override fun observeChapter(url: String): Flow<PrefetchResult?> {
        return results.map { it[url] }
    }

    override fun observeAll(): Flow<Map<String, PrefetchResult>> {
        return results
    }

    override fun prune() {
        pruneCalled = true
    }
}
