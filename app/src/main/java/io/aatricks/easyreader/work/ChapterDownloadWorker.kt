package io.aatricks.easyreader.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.isStrictOfflineReady
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.UrlSanitizer

/**
 * Persists chapter downloads to WorkManager so they survive process death and OS-level
 * backgrounding. Bulk downloads and per-chapter retries enqueue one of these per chapter.
 *
 * Progress is published via `setProgress` so observers (LibraryViewModel) can mirror the
 * latest [PrefetchResult] into the UI without keeping the prefetch work tied to the VM
 * lifecycle.
 */
@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val contentRepository: ContentRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadStatusReconciler: DownloadStatusReconciler
) : CoroutineWorker(appContext, params) {

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_CHAPTER_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "missing chapter url, skipping")
            return Result.failure()
        }

        val safeUrl = UrlSanitizer.sanitize(url)
        Log.d(TAG, "starting download url=$safeUrl runAttempt=$runAttemptCount")

        // Skip when a previous worker run (or a foreground request that already finished)
        // has already completed the download — avoids re-inspect, re-record of permanent
        // failures, and churn on the chapterPrefetchMutex for chapters that are already
        // complete.
        val existing = runCatching { contentRepository.inspectDownload(url) }.getOrNull()
        if (existing?.isStrictOfflineReady() == true) {
            Log.d(TAG, "already complete, skipping worker url=$safeUrl")
            publishProgress(existing)
            // Reconcile so an orphaned isDownloaded=false (e.g. VM was cancelled before its
            // own reconcile ran) gets promoted off the worker's durable execution.
            runCatching { reconcileFlag(url, existing) }
            return Result.success(existing.toTerminalData())
        }

        contentRepository.beginUserDownload(url)
        return try {
            var publishedTerminal = false
            val result = ChapterDownloadLimiter.withPermit {
                contentRepository.downloadChapter(url) { progress ->
                    if (!progress.isInProgress) publishedTerminal = true
                    publishProgress(progress)
                }
            }

            if (!publishedTerminal) {
                publishProgress(result)
            }
            // Worker is the durable second writer for the DB flag. Flag must track on-disk reality
            // even if the user cleared the download mid-run.
            runCatching { reconcileFlag(url, contentRepository.inspectDownload(url)) }
            val terminal = result.toTerminalData()
            // Treat "complete with permanent failures" as success — the loop has nothing more
            // to do. The badge logic separately downgrades it via hasPermanentFailures.
            when {
                result.isComplete -> Result.success(terminal)
                result.isRetryable -> {
                    if (runAttemptCount < MAX_RUN_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure(terminal)
                    }
                }
                else -> Result.failure(terminal)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "download error url=$safeUrl message=${t.message}")
            if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            contentRepository.endUserDownload(url)
        }
    }

    private suspend fun reconcileFlag(url: String, result: PrefetchResult) {
        val item = libraryRepository.getItemByUrl(url) ?: return
        downloadStatusReconciler.reconcile(item, result, wasUserInspect = true)
    }

    private suspend fun publishProgress(progress: PrefetchResult) {
        setProgress(
            workDataOf(
                KEY_CHAPTER_URL to progress.url,
                KEY_HTML_CACHED to progress.htmlCached,
                KEY_TOTAL_IMAGES to progress.totalImages,
                KEY_CACHED_IMAGES to progress.cachedImages,
                KEY_IS_COMPLETE to progress.isComplete,
                KEY_IS_IN_PROGRESS to progress.isInProgress,
                KEY_IS_RETRYABLE to progress.isRetryable,
                KEY_IS_PERSISTENT_DOWNLOAD to progress.isPersistentDownload,
                KEY_HAS_PERMANENT_FAILURES to progress.hasPermanentFailures
            )
        )
    }

    private fun PrefetchResult.toTerminalData() = workDataOf(
        KEY_CHAPTER_URL to url,
        KEY_HTML_CACHED to htmlCached,
        KEY_TOTAL_IMAGES to totalImages,
        KEY_CACHED_IMAGES to cachedImages,
        KEY_IS_COMPLETE to isComplete,
        KEY_IS_IN_PROGRESS to false,
        KEY_IS_RETRYABLE to isRetryable,
        KEY_IS_PERSISTENT_DOWNLOAD to isPersistentDownload,
        KEY_HAS_PERMANENT_FAILURES to hasPermanentFailures
    )

    companion object {
        private const val TAG = "ChapterDownloadWorker"
        private const val MAX_RUN_ATTEMPTS = 5

        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_HTML_CACHED = "html_cached"
        const val KEY_TOTAL_IMAGES = "total_images"
        const val KEY_CACHED_IMAGES = "cached_images"
        const val KEY_IS_COMPLETE = "is_complete"
        const val KEY_IS_IN_PROGRESS = "is_in_progress"
        const val KEY_IS_RETRYABLE = "is_retryable"
        const val KEY_IS_PERSISTENT_DOWNLOAD = "is_persistent_download"
        const val KEY_HAS_PERMANENT_FAILURES = "has_permanent_failures"
    }
}
