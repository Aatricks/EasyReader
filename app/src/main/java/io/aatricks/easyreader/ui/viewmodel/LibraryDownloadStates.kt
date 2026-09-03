package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.DownloadStatusReconciler
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.work.ChapterDownloadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Owns the per-chapter download/cache badge state and its reconciliation (queue observation,
 * on-demand reconcile + auto-resume of incomplete downloads, and semaphore-limited cache-state refresh),
 * extracted from LibraryViewModel.
 */
class LibraryDownloadStates(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val contentRepository: ContentRepository,
    private val downloadStatusReconciler: DownloadStatusReconciler,
    private val downloadQueue: ChapterDownloadQueue,
) {
    private val _chapterCacheStates = MutableStateFlow<Map<String, PrefetchResult>>(emptyMap())
    val chapterCacheStates: StateFlow<Map<String, PrefetchResult>> = _chapterCacheStates.asStateFlow()

    /**
     * Chapters whose queued download ended without completing. A failed download leaves no manifest,
     * so the disk inspect that owns terminal badge states cannot tell "never downloaded" from
     * "download failed" — this set carries that distinction beside [chapterCacheStates] rather than
     * inside it. Cleared when the chapter is re-queued or dropped from the library.
     */
    private val _downloadFailures = MutableStateFlow<Set<String>>(emptySet())
    val downloadFailures: StateFlow<Set<String>> = _downloadFailures.asStateFlow()

    /** One-shot prompt for the snackbar that offers to (re-)queue a download. */
    private val _retryPrompt = MutableStateFlow<DownloadRetryPrompt?>(null)
    val retryPrompt: StateFlow<DownloadRetryPrompt?> = _retryPrompt.asStateFlow()

    fun consumeRetryPrompt() {
        _retryPrompt.value = null
    }

    /** Cancels the queued work, clears the chapter from disk and reconciles the DB flag. */
    suspend fun removeDownload(item: LibraryItem) {
        downloadQueue.cancel(item.url)
        runCatching {
            contentRepository.clearDownload(item.url)
            downloadStatusReconciler.reconcile(
                item,
                contentRepository.inspectDownload(item.url),
                wasUserInspect = true
            )
        }.onSuccess {
            refreshChapterCacheStates(listOf(item.url))
            _retryPrompt.value = DownloadRetryPrompt("Download removed", "Re-download", listOf(item.url))
        }
    }

    private var downloadedReconciliationJob: Job? = null
    private var queueInProgressUrls: Set<String> = emptySet()
    private var lastQueueResults: Map<String, PrefetchResult> = emptyMap()
    private val resumeAttempts = mutableMapOf<String, Int>()

    companion object {
        private const val TAG = "LibraryDownloadStates"
        private const val CACHE_STATE_REFRESH_CONCURRENCY = 6
        private const val MAX_AUTO_RESUME_ATTEMPTS = 2
    }

    init {
        scope.launch {
            downloadQueue.observeAll().collect { results ->
                val inProgress = results.filterValues { it.isInProgress }
                // A chapter whose worker finishes in milliseconds can have its first observed
                // emission already terminal, so it never enters queueInProgressUrls. Anything we
                // optimistically marked pending locally is ended by a terminal queue result too.
                // Finished work stays in WorkManager, so only terminal results we have not already
                // processed count — a repeat must not re-end a download that was just re-queued.
                val terminal = results.filterValues { !it.isInProgress }
                    .filterNot { (url, result) -> lastQueueResults[url] == result }
                lastQueueResults = results
                val locallyPendingEnded = terminal.keys.filter {
                    _chapterCacheStates.value[it]?.isInProgress == true
                }
                val wasInProgress = queueInProgressUrls + locallyPendingEnded
                val ended = (queueInProgressUrls - inProgress.keys) + locallyPendingEnded
                queueInProgressUrls = inProgress.keys
                if (inProgress.isNotEmpty()) {
                    _chapterCacheStates.update { it + inProgress }
                }
                val fresh = failedDownloadUrls(terminal, wasInProgress) - _downloadFailures.value
                if (fresh.isNotEmpty()) {
                    _downloadFailures.update { it + fresh }
                    _retryPrompt.value = downloadFailurePrompt(fresh.toList())
                }
                if (ended.isNotEmpty()) {
                    refreshChapterCacheStates(ended)   // disk inspect is the only writer of terminal states
                }
            }
        }
    }

    fun setCacheState(result: PrefetchResult) {
        _chapterCacheStates.update { current ->
            if (!result.isInProgress && result.url in queueInProgressUrls) {
                current
            } else {
                current + (result.url to result)
            }
        }
    }

    fun markPendingAndEnqueue(url: String, replaceExisting: Boolean = false): Boolean {
        val previous = _chapterCacheStates.value[url]
        val pendingResult = (previous ?: PrefetchResult(
            url = url,
            htmlCached = false,
            totalImages = 0,
            cachedImages = 0,
            isComplete = false
        )).copy(
            isInProgress = true,
            isRetryable = false,
            isPersistentDownload = true
        )
        _chapterCacheStates.update { it + (url to pendingResult) }
        _downloadFailures.update { it - url }
        val success = downloadQueue.enqueue(url, replaceExisting)
        if (!success) {
            _chapterCacheStates.update { current ->
                if (previous != null) {
                    current + (url to previous)
                } else {
                    current - url
                }
            }
        }
        return success
    }

    fun removeCacheStates(urls: Collection<String>) {
        val removed = urls.toSet()
        _chapterCacheStates.update { it - removed }
        _downloadFailures.update { it - removed }
    }

    fun reconcileDownloadedItemsOnDemand() {
        if (downloadedReconciliationJob?.isActive == true) return
        downloadedReconciliationJob = scope.launch {
            val snapshot = runCatching {
                @Suppress("USELESS_ELVIS")
                repository.getAllItemsSnapshot() ?: emptyList()
            }.getOrDefault(emptyList())
            val urls = snapshot.asSequence().map { it.url }.filter { it.isNotBlank() }.toList()
            if (urls.isEmpty()) return@launch
            val results = refreshChapterCacheStatesSuspend(urls)
            val wantedUrls = snapshot.asSequence().filter { it.isDownloaded }.map { it.url }.toSet()
            autoResumeIncompleteDownloads(results, wantedUrls)
        }
    }

    private suspend fun autoResumeIncompleteDownloads(results: List<PrefetchResult>, userWantedUrls: Set<String>) {
        // Two ways a chapter qualifies as an in-flight-but-incomplete download:
        //  - inspect reports it as a persistent download with images still missing
        //  - DB remembers the user asked for it (isDownloaded=true) but html cache was lost
        //    (cache eviction, manual clear) — these are invisible to the first condition
        //    because isPersistentDownload requires htmlCached=true.
        val targets = results.filter { shouldAutoResume(it, userWantedUrls) }
        if (targets.isEmpty()) return
        Log.d(TAG, "Auto-resuming ${targets.size} incomplete downloads")
        targets.forEach { state ->
            val attempts = resumeAttempts[state.url] ?: 0
            if (attempts >= MAX_AUTO_RESUME_ATTEMPTS) return@forEach
            val item = repository.getItemByUrl(state.url)
            if (item == null) return@forEach

            val success = markPendingAndEnqueue(state.url)
            if (success) {
                resumeAttempts[state.url] = attempts + 1
            }
        }
    }

    private fun shouldAutoResume(result: PrefetchResult, userWantedUrls: Set<String>): Boolean {
        val inProgressOrNotRetryable = result.isInProgress || !result.isRetryable || result.hasPermanentFailures
        if (inProgressOrNotRetryable) return false

        val wanted = result.isPersistentDownload || result.url in userWantedUrls
        val missingHtml = !result.htmlCached
        val missingImages = result.htmlCached &&
            result.totalImages > 0 &&
            result.cachedImages < result.totalImages
        return wanted && (missingHtml || missingImages)
    }

    fun refreshChapterCacheStates(urls: Collection<String>) {
        scope.launch {
            refreshChapterCacheStatesSuspend(urls)
        }
    }

    private suspend fun refreshChapterCacheStatesSuspend(urls: Collection<String>): List<PrefetchResult> {
        val targetUrls = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (targetUrls.isEmpty()) return emptyList()

        val libraryItemsByUrl = repository.libraryItems.value
            .asSequence()
            .associateBy { it.url }

        val results = supervisorScope {
            val semaphore = Semaphore(CACHE_STATE_REFRESH_CONCURRENCY)
            targetUrls.map { url ->
                async {
                    semaphore.withPermit {
                        val item = libraryItemsByUrl[url]
                        val downloadResult = if (item != null) {
                            runCatching { contentRepository.inspectDownload(url) }.getOrNull()
                        } else {
                            null
                        }
                        val useDownloadResult = item?.isDownloaded == true || downloadResult.hasDownloadEvidence()
                        val result = if (useDownloadResult) {
                            downloadResult
                        } else {
                            runCatching { contentRepository.inspectCache(url) }.getOrNull()
                        }
                        if (item != null && result != null && !result.isInProgress) {
                            downloadStatusReconciler.reconcile(
                                item,
                                result,
                                wasUserInspect = useDownloadResult
                            )
                        }
                        result
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (results.isNotEmpty()) {
            _chapterCacheStates.update { current ->
                val filteredResults = results.filterNot { result ->
                    !result.isInProgress && result.url in queueInProgressUrls
                }
                current + filteredResults.associateBy { it.url }
            }
        }
        return results
    }
}

/**
 * Downloads we were tracking that ended without completing: the worker gave up after its retries.
 * Cancellations reach the queue with the same shape, but the only cancel paths (remove download,
 * delete item) act on chapters that are not in flight.
 */
private fun failedDownloadUrls(
    terminal: Map<String, PrefetchResult>,
    wasInProgress: Set<String>
): Set<String> = terminal.asSequence()
    .filter { (url, result) -> url in wasInProgress && !result.isComplete }
    .mapTo(mutableSetOf()) { it.key }

private fun downloadFailurePrompt(failed: List<String>) = DownloadRetryPrompt(
    message = if (failed.size == 1) "Download failed" else "${failed.size} downloads failed",
    actionLabel = "Retry",
    urls = failed
)

private fun PrefetchResult?.hasDownloadEvidence(): Boolean =
    this != null && (htmlCached || totalImages > 0 || cachedImages > 0 || isInProgress)
