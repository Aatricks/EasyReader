package io.aatricks.easyreader.data.repository

import android.util.Log
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.isStrictOfflineReady
import io.aatricks.easyreader.util.UrlSanitizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical writer for the [LibraryItem.isDownloaded] flag. Every producer of a
 * download/inspect result (VM in-process prefetch, ChapterDownloadWorker, startup
 * verifier, refresh) routes through here so the badge, the DB flag, and the
 * on-disk image state cannot disagree.
 *
 * Promotion: flag → true when [isFullyDownloaded].
 *
 * Demotion: flag → false when the result is terminal (not in progress) AND came
 * from a downloads-tier inspect (`wasUserInspect = true`) AND the chapter is not
 * fully present on disk. This covers three real failure modes:
 *   - hasPermanentFailures=true (4xx images recorded in the failure store)
 *   - isComplete=false (transient failures that never recovered after retries)
 *   - isPersistentDownload=false (downloads-tier HTML was lost / never written)
 *
 * SPECULATIVE / all-tier inspects pass `wasUserInspect = false` so cache-tier
 * lookups never knock the flag down.
 */
@Singleton
class DownloadStatusReconciler @Inject constructor(
    private val libraryRepository: LibraryRepository
) {
    fun isFullyDownloaded(result: PrefetchResult): Boolean =
        result.isStrictOfflineReady()

    @Suppress("ReturnCount")
    suspend fun reconcile(
        item: LibraryItem,
        result: PrefetchResult,
        wasUserInspect: Boolean
    ) {
        if (isFullyDownloaded(result)) {
            val action = if (!item.isDownloaded) "promote" else "noop-already-true"
            logAction(item, result, wasUserInspect, action)
            if (!item.isDownloaded) {
                libraryRepository.markDownloaded(item.id, true)
            }
            return
        }
        if (!wasUserInspect) {
            logAction(item, result, wasUserInspect, "noop-not-user-inspect")
            return
        }
        if (result.isInProgress) {
            logAction(item, result, wasUserInspect, "noop-in-progress")
            return
        }
        val action = if (item.isDownloaded) "demote" else "noop-already-false"
        logAction(item, result, wasUserInspect, action)
        if (item.isDownloaded) {
            libraryRepository.markDownloaded(item.id, false)
        }
    }

    // TODO(verification): remove this logging after the bulk-download regression is
    // confirmed fixed on a real device. The fields here are the inputs to every
    // download-status decision so a logcat trace tells us exactly why a chapter
    // ended up in the wrong state if the bug recurs.
    private fun logAction(
        item: LibraryItem,
        result: PrefetchResult,
        wasUserInspect: Boolean,
        action: String
    ) {
        Log.w(
            TAG,
            "url=${UrlSanitizer.sanitize(item.url)} action=$action " +
                "item.isDownloaded=${item.isDownloaded} " +
                "isComplete=${result.isComplete} hasPerm=${result.hasPermanentFailures} " +
                "inProgress=${result.isInProgress} persistent=${result.isPersistentDownload} " +
                "userInspect=$wasUserInspect cached=${result.cachedImages}/${result.totalImages}"
        )
    }

    private companion object {
        private const val TAG = "DownloadReconciler"
    }

    suspend fun reconcile(
        url: String,
        result: PrefetchResult,
        wasUserInspect: Boolean
    ) {
        val item = libraryRepository.getItemByUrl(url) ?: return
        reconcile(item, result, wasUserInspect)
    }
}
