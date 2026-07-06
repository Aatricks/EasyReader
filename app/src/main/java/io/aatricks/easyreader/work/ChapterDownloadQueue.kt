package io.aatricks.easyreader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.util.CacheKeyUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the WorkManager API for chapter downloads so the rest of the app deals in URLs and
 * `PrefetchResult` rather than `WorkRequest` / `WorkInfo`. Enqueueing is keyed by chapter
 * URL via unique work names, so a duplicate enqueue while a download is in flight either
 * coalesces (KEEP) or replaces (REPLACE) deterministically.
 */
interface ChapterDownloadQueue {
    fun enqueue(url: String, replaceExisting: Boolean = false): Boolean
    fun cancel(url: String)
    fun cancelAll()
    fun observeChapter(url: String): Flow<PrefetchResult?>
    fun observeAll(): Flow<Map<String, PrefetchResult>>
    fun prune()

    companion object {
        const val TAG_CHAPTER_DOWNLOAD = "chapter-download"
    }
}

@Singleton
class WorkManagerChapterDownloadQueue @Inject constructor(
    @ApplicationContext private val context: Context
) : ChapterDownloadQueue {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun enqueue(url: String, replaceExisting: Boolean): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .addTag(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
                .addTag(tagFor(url))
                .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_URL to url))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(uniqueName(url), policy, request)
            true
        }.getOrDefault(false)
    }

    override fun prune() {
        runCatching {
            workManager.pruneWork()
        }
    }

    override fun cancel(url: String) {
        if (url.isBlank()) return
        workManager.cancelUniqueWork(uniqueName(url))
    }

    override fun cancelAll() {
        runCatching {
            workManager.cancelAllWorkByTag(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
        }
    }

    /** Live progress for a single chapter, regardless of which WorkInfo currently owns it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChapter(url: String): Flow<PrefetchResult?> {
        if (url.isBlank()) return flowOf(null)
        return workManager.getWorkInfosForUniqueWorkFlow(uniqueName(url))
            .map { infos -> infos.lastOrNull() }
            .distinctUntilChanged()
            .flatMapLatest { info -> flowOf(info?.toPrefetchResult(url)) }
    }

    /** All chapter downloads currently tracked by WorkManager. */
    override fun observeAll(): Flow<Map<String, PrefetchResult>> {
        return workManager.getWorkInfosByTagFlow(ChapterDownloadQueue.TAG_CHAPTER_DOWNLOAD)
            .map { infos ->
                infos.asSequence()
                    .mapNotNull { info ->
                        val url = info.chapterUrl() ?: return@mapNotNull null
                        url to info
                    }
                    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                    .mapValues { (url, urlInfos) ->
                        urlInfos.maxWithOrNull(compareBy<WorkInfo> { it.downloadObservationRank() })
                            ?.toPrefetchResult(url)
                    }
                    .entries
                    .mapNotNull { (url, result) ->
                        result?.let { url to it }
                    }
                    .toMap()
            }
            .distinctUntilChanged()
    }

}

private const val UNIQUE_PREFIX = "chapter-download:"
private const val URL_TAG_PREFIX = "chapter-url:"

private fun uniqueName(url: String): String = "$UNIQUE_PREFIX${urlKey(url)}"
private fun tagFor(url: String): String = "$URL_TAG_PREFIX${urlKey(url)}"
private fun urlKey(url: String): String = CacheKeyUtils.keyFor(url)

private fun WorkInfo.chapterUrl(): String? {
    progress.getString(ChapterDownloadWorker.KEY_CHAPTER_URL)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return outputData.getString(ChapterDownloadWorker.KEY_CHAPTER_URL)?.takeIf { it.isNotBlank() }
}

private fun WorkInfo.toPrefetchResult(fallbackUrl: String): PrefetchResult {
    val payload: Data = if (progress.keyValueMap.isNotEmpty()) progress else outputData
    val url = payload.getString(ChapterDownloadWorker.KEY_CHAPTER_URL) ?: fallbackUrl
    val inProgress = state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.ENQUEUED ||
        state == WorkInfo.State.BLOCKED
    // For terminal states without progress data (e.g. failed pre-publish), fall back to
    // sensible defaults that won't promote the DB isDownloaded flag.
    return PrefetchResult(
        url = url,
        htmlCached = payload.getBoolean(ChapterDownloadWorker.KEY_HTML_CACHED, false),
        totalImages = payload.getInt(ChapterDownloadWorker.KEY_TOTAL_IMAGES, 0),
        cachedImages = payload.getInt(ChapterDownloadWorker.KEY_CACHED_IMAGES, 0),
        isComplete = payload.getBoolean(ChapterDownloadWorker.KEY_IS_COMPLETE, false) &&
            state == WorkInfo.State.SUCCEEDED,
        isInProgress = inProgress,
        isRetryable = payload.getBoolean(ChapterDownloadWorker.KEY_IS_RETRYABLE, state != WorkInfo.State.SUCCEEDED),
        isPersistentDownload = payload.getBoolean(ChapterDownloadWorker.KEY_IS_PERSISTENT_DOWNLOAD, true),
        hasPermanentFailures = payload.getBoolean(ChapterDownloadWorker.KEY_HAS_PERMANENT_FAILURES, false)
    )
}

private const val RANK_RUNNING = 5
private const val RANK_ENQUEUED = 4
private const val RANK_SUCCEEDED = 3
private const val RANK_FAILED = 2
private const val RANK_CANCELLED = 1

private fun WorkInfo.downloadObservationRank(): Int = when (state) {
    WorkInfo.State.RUNNING -> RANK_RUNNING
    WorkInfo.State.ENQUEUED,
    WorkInfo.State.BLOCKED -> RANK_ENQUEUED
    WorkInfo.State.SUCCEEDED -> RANK_SUCCEEDED
    WorkInfo.State.FAILED -> RANK_FAILED
    WorkInfo.State.CANCELLED -> RANK_CANCELLED
}
