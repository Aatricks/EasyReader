package io.aatricks.easyreader.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class ChapterDownloadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `worker runs prefetch and reports success when chapter completes`() = runBlocking {
        val chapterUrl = "https://example.com/work-success"
        val contentRepository = mock<ContentRepository>()
        // Inspect must indicate the chapter is incomplete so the worker actually runs the
        // prefetch path instead of short-circuiting on the already-complete check.
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(
            PrefetchResult(url = chapterUrl, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
        )
        whenever(contentRepository.prefetchWithProgress(eq(chapterUrl), eq(PrefetchMode.USER_REQUESTED), any()))
            .thenReturn(
                PrefetchResult(
                    url = chapterUrl,
                    htmlCached = true,
                    totalImages = 5,
                    cachedImages = 5,
                    isComplete = true,
                    isPersistentDownload = true
                )
            )

        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(context)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to chapterUrl))
            .setWorkerFactory(workerFactoryWith(contentRepository))
            .build()

        val result = worker.doWork()
        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        verify(contentRepository).beginUserDownload(chapterUrl)
        verify(contentRepository).endUserDownload(chapterUrl)
    }

    @Test
    fun `worker short-circuits when chapter already complete`() = runBlocking {
        val chapterUrl = "https://example.com/work-already-done"
        val contentRepository = mock<ContentRepository>()
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(
            PrefetchResult(
                url = chapterUrl,
                htmlCached = true,
                totalImages = 4,
                cachedImages = 4,
                isComplete = true,
                isPersistentDownload = true,
                hasPermanentFailures = false
            )
        )

        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(context)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to chapterUrl))
            .setWorkerFactory(workerFactoryWith(contentRepository))
            .build()

        val result = worker.doWork()
        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        // Confirm we did NOT run the prefetch — in-process call had already finished it.
        verify(contentRepository, org.mockito.kotlin.never())
            .prefetchWithProgress(eq(chapterUrl), eq(PrefetchMode.USER_REQUESTED), any())
        verify(contentRepository, org.mockito.kotlin.never()).beginUserDownload(chapterUrl)
    }

    @Test
    fun `worker returns retry when prefetch is retryable`() = runBlocking {
        val chapterUrl = "https://example.com/work-retry"
        val contentRepository = mock<ContentRepository>()
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(
            PrefetchResult(url = chapterUrl, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
        )
        whenever(contentRepository.prefetchWithProgress(eq(chapterUrl), eq(PrefetchMode.USER_REQUESTED), any()))
            .thenReturn(
                PrefetchResult(
                    url = chapterUrl,
                    htmlCached = true,
                    totalImages = 5,
                    cachedImages = 2,
                    isComplete = false,
                    isRetryable = true,
                    isPersistentDownload = true
                )
            )

        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(context)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to chapterUrl))
            .setWorkerFactory(workerFactoryWith(contentRepository))
            .build()

        val result = worker.doWork()
        assertTrue("expected Retry, got $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `worker promotes DB flag after successful prefetch when item not yet downloaded`(): Unit = runBlocking {
        val chapterUrl = "https://example.com/work-promote"
        val contentRepository = mock<ContentRepository>()
        val libraryRepository = mock<LibraryRepository>()
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(
            PrefetchResult(url = chapterUrl, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
        )
        whenever(contentRepository.prefetchWithProgress(eq(chapterUrl), eq(PrefetchMode.USER_REQUESTED), any()))
            .thenReturn(
                PrefetchResult(
                    url = chapterUrl,
                    htmlCached = true,
                    totalImages = 3,
                    cachedImages = 3,
                    isComplete = true,
                    isPersistentDownload = true
                )
            )
        whenever(libraryRepository.getItemByUrl(chapterUrl)).thenReturn(
            io.aatricks.easyreader.data.model.LibraryItem(
                id = "lib-id",
                title = "Chapter promote",
                url = chapterUrl,
                isDownloaded = false
            )
        )

        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(context)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to chapterUrl))
            .setWorkerFactory(workerFactoryWith(contentRepository, libraryRepository))
            .build()

        val result = worker.doWork()
        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        verify(libraryRepository).markDownloaded("lib-id", true)
    }

    @Test
    fun `worker short-circuit still reconciles flag for orphaned downloads`(): Unit = runBlocking {
        val chapterUrl = "https://example.com/work-shortcircuit-promote"
        val contentRepository = mock<ContentRepository>()
        val libraryRepository = mock<LibraryRepository>()
        // Inspect short-circuits because in-process call already finished, but the VM was
        // cancelled before it could write the flag. Worker must still write it.
        whenever(contentRepository.inspectDownload(chapterUrl)).thenReturn(
            PrefetchResult(
                url = chapterUrl,
                htmlCached = true,
                totalImages = 4,
                cachedImages = 4,
                isComplete = true,
                isPersistentDownload = true
            )
        )
        whenever(libraryRepository.getItemByUrl(chapterUrl)).thenReturn(
            io.aatricks.easyreader.data.model.LibraryItem(
                id = "lib-orphan",
                title = "Chapter orphan",
                url = chapterUrl,
                isDownloaded = false
            )
        )

        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(context)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to chapterUrl))
            .setWorkerFactory(workerFactoryWith(contentRepository, libraryRepository))
            .build()

        val result = worker.doWork()
        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        verify(libraryRepository).markDownloaded("lib-orphan", true)
    }

    @Test
    fun `queue enqueueUniqueWork dedupes by chapter url`() = runBlocking {
        val queue = WorkManagerChapterDownloadQueue(context)
        val url = "https://example.com/work-dedup"

        queue.enqueue(url)
        queue.enqueue(url) // KEEP policy: second call must coalesce, not enqueue twice.

        val infos = WorkManager.getInstance(context)
            .getWorkInfosByTag(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
            .get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `queue cancel removes the enqueued work`() = runBlocking {
        val queue = WorkManagerChapterDownloadQueue(context)
        val url = "https://example.com/work-cancel"

        queue.enqueue(url)
        queue.cancel(url)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosByTag(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
            .get()
        val live = infos.filterNot { it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.SUCCEEDED }
        assertEquals(0, live.size)
    }

    @Test
    fun `replaceExisting leaves at most one live work for the chapter`() {
        val queue = WorkManagerChapterDownloadQueue(context)
        val url = "https://example.com/work-replace"

        queue.enqueue(url)
        queue.enqueue(url, replaceExisting = true)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosByTag(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
            .get()
        // REPLACE policy must guarantee at most one live job for the chapter — under the
        // synchronous executor either both finish SUCCEEDED, the first is CANCELLED, or the
        // second is ENQUEUED. None of those leave two RUNNING/ENQUEUED at once.
        val live = infos.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        assertTrue("expected at most one live work, got $infos", live <= 1)
    }

    private fun workerFactoryWith(
        contentRepository: ContentRepository,
        libraryRepository: LibraryRepository = mock(),
        reconciler: DownloadStatusReconciler = DownloadStatusReconciler(libraryRepository)
    ): androidx.work.WorkerFactory {
        return object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return if (workerClassName == ChapterDownloadWorker::class.java.name) {
                    ChapterDownloadWorker(
                        appContext,
                        workerParameters,
                        contentRepository,
                        libraryRepository,
                        reconciler
                    )
                } else null
            }
        }
    }
}
