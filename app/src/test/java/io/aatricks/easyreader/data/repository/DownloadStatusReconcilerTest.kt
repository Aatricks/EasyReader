package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DownloadStatusReconcilerTest {

    private val libraryRepository: LibraryRepository = mock()
    private lateinit var reconciler: DownloadStatusReconciler

    private val downloadedItem = LibraryItem(
        id = "id-downloaded",
        title = "Chapter D",
        url = "https://example.com/chapter-d",
        isDownloaded = true
    )

    private val notDownloadedItem = LibraryItem(
        id = "id-not-downloaded",
        title = "Chapter N",
        url = "https://example.com/chapter-n",
        isDownloaded = false
    )

    @Before
    fun setup() {
        reconciler = DownloadStatusReconciler(libraryRepository)
    }

    @Test
    fun `promotes when fully downloaded on disk`() = runTest {
        val result = PrefetchResult(
            url = notDownloadedItem.url,
            htmlCached = true,
            totalImages = 3,
            cachedImages = 3,
            isComplete = true,
            isPersistentDownload = true,
            hasPermanentFailures = false
        )

        reconciler.reconcile(notDownloadedItem, result, wasUserInspect = true)

        verify(libraryRepository).markDownloaded(notDownloadedItem.id, true)
    }

    @Test
    fun `idempotent when already promoted`() = runTest {
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 3,
            cachedImages = 3,
            isComplete = true,
            isPersistentDownload = true
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = true)

        verify(libraryRepository, never()).markDownloaded(eq(downloadedItem.id), eq(true))
        verify(libraryRepository, never()).markDownloaded(eq(downloadedItem.id), eq(false))
    }

    @Test
    fun `demotes when permanent failures appear`() = runTest {
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 3,
            isComplete = true,
            isPersistentDownload = true,
            hasPermanentFailures = true
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = true)

        verify(libraryRepository).markDownloaded(downloadedItem.id, false)
    }

    @Test
    fun `demotes when persistent inspect is terminal but incomplete`() = runTest {
        // Transient failures (network) that never recovered — no permanent failures
        // recorded, but the chapter is still not fully on disk. Previously this case
        // left the flag stuck at true forever.
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 3,
            isComplete = false,
            isInProgress = false,
            isPersistentDownload = true,
            hasPermanentFailures = false
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = true)

        verify(libraryRepository).markDownloaded(downloadedItem.id, false)
    }

    @Test
    fun `demotes when downloads-tier HTML is missing`() = runTest {
        // A persistentOnly inspect that comes back with htmlCached=false yields
        // isPersistentDownload=false — i.e. the download HTML was lost between launches.
        // The chapter cannot be openable offline so the flag must come down.
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false,
            isInProgress = false,
            isPersistentDownload = false
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = true)

        verify(libraryRepository).markDownloaded(downloadedItem.id, false)
    }

    @Test
    fun `does not demote while inspect is still in progress`() = runTest {
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 3,
            isComplete = false,
            isInProgress = true,
            isPersistentDownload = true
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = true)

        verify(libraryRepository, never()).markDownloaded(eq(downloadedItem.id), eq(false))
    }

    @Test
    fun `does not demote on non-user inspect`() = runTest {
        // A SPECULATIVE / all-tier inspect doesn't authoritatively reflect the downloads
        // tier, so it must not knock the flag down.
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 5,
            cachedImages = 3,
            isComplete = false,
            isInProgress = false,
            isPersistentDownload = false
        )

        reconciler.reconcile(downloadedItem, result, wasUserInspect = false)

        verify(libraryRepository, never()).markDownloaded(eq(downloadedItem.id), eq(false))
    }

    @Test
    fun `url overload looks up item via repository`() = runTest {
        whenever(libraryRepository.getItemByUrl(downloadedItem.url)).thenReturn(downloadedItem)
        val result = PrefetchResult(
            url = downloadedItem.url,
            htmlCached = true,
            totalImages = 4,
            cachedImages = 4,
            isComplete = true,
            isPersistentDownload = true
        )

        reconciler.reconcile(downloadedItem.url, result, wasUserInspect = true)

        verify(libraryRepository).getItemByUrl(downloadedItem.url)
    }

    @Test
    fun `url overload is no-op when item is absent`() = runTest {
        whenever(libraryRepository.getItemByUrl("missing")).thenReturn(null)

        reconciler.reconcile(
            "missing",
            PrefetchResult(
                url = "missing",
                htmlCached = true,
                totalImages = 1,
                cachedImages = 1,
                isComplete = true,
                isPersistentDownload = true
            ),
            wasUserInspect = true
        )

        verify(libraryRepository, never()).markDownloaded(any(), any())
    }
}

private inline fun <reified T> any(): T = org.mockito.kotlin.any()
