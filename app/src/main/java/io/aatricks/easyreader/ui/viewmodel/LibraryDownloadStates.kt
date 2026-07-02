package io.aatricks.easyreader.ui.viewmodel

import android.util.Log
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
    private var downloadedReconciliationJob: Job? = null

    companion object {
        private const val TAG = "LibraryDownloadStates"
        private const val CACHE_STATE_REFRESH_CONCURRENCY = 6
    }

    init {
        scope.launch {
            downloadQueue.observeAll().collect { results ->
                if (results.isEmpty()) return@collect
                _chapterCacheStates.update { current -> current + results }
            }
        }
    }

    fun setCacheState(result: PrefetchResult) {
        _chapterCacheStates.update { current ->
            current + (result.url to result)
        }
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

    private fun autoResumeIncompleteDownloads(results: List<PrefetchResult>, userWantedUrls: Set<String>) {
        // Two ways a chapter qualifies as an in-flight-but-incomplete download:
        //  - inspect reports it as a persistent download with images still missing
        //  - DB remembers the user asked for it (isDownloaded=true) but html cache was lost
        //    (cache eviction, manual clear) — these are invisible to the first condition
        //    because isPersistentDownload requires htmlCached=true.
        val targets = results.filter { result ->
            if (result.isInProgress) return@filter false
            val wanted = result.isPersistentDownload || result.url in userWantedUrls
            if (!wanted) return@filter false
            val missingHtml = !result.htmlCached
            val missingImages = result.htmlCached &&
                result.totalImages > 0 &&
                result.cachedImages < result.totalImages
            missingHtml || missingImages
        }
        if (targets.isEmpty()) return
        Log.d(TAG, "Auto-resuming ${targets.size} incomplete downloads")
        targets.forEach { state ->
            setCacheState(
                state.copy(
                    isInProgress = true,
                    isRetryable = false,
                    isPersistentDownload = true
                )
            )
            downloadQueue.enqueue(state.url)
        }
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
                current + results.associateBy { it.url }
            }
        }
        return results
    }
}

private fun PrefetchResult?.hasDownloadEvidence(): Boolean =
    this != null && (htmlCached || totalImages > 0 || cachedImages > 0 || isInProgress)
