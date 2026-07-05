package io.aatricks.easyreader.ui.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the resolved-image-dimension pipeline extracted from ReaderViewModel.
 *
 * [applyContentDimensions] is the ViewModel's in-memory content rebuild (it mutates the reader
 * ui state, which only the ViewModel can do); everything else — the fine-grained Compose state,
 * the prompt off-main cache flush, and the trailing debounce — lives here.
 */
class ImageDimensionManager(
    private val scope: CoroutineScope,
    private val imageDimensionCache: ImageDimensionCacheRepository,
    private val applyContentDimensions: (Map<String, Pair<Int, Int>>) -> Unit,
) {
    // Resolved intrinsic dimensions keyed by image URL, one MutableState per URL. A single
    // SnapshotStateMap would NOT give per-image granularity — its read tracking is per-map, so
    // any write invalidates every composed reader. With one State per URL, a first-decode write
    // recomposes only that image — and an item scrolled away and back is sized correctly on its
    // FIRST composition (no collapse to the loading placeholder + relayout), which is what keeps
    // fast up/down dragging smooth. The debounced content rebuild stays only for persistence /
    // restore math.
    private val dimensionStates = ConcurrentHashMap<String, MutableState<Pair<Int, Int>?>>()

    private val pendingImageDimensions = LinkedHashMap<String, Pair<Int, Int>>()
    private val contentDimUpdates = LinkedHashMap<String, Pair<Int, Int>>()
    private var dimensionFlushJob: Job? = null
    private var contentDimApplyJob: Job? = null

    companion object {
        private const val TAG = "ImageDimensionManager"
        private const val IMAGE_DIMENSION_FLUSH_DELAY_MS = 100L
        private const val CONTENT_DIM_APPLY_DEBOUNCE_MS = 350L
    }

    /**
     * Composable-friendly per-URL dimension state: a write invalidates only readers of THIS url.
     * computeIfAbsent (not getOrPut, which is non-atomic on ConcurrentHashMap) guarantees a
     * single State instance per url, so subscription and later writes always meet.
     */
    fun dimensionState(imageUrl: String): State<Pair<Int, Int>?> =
        dimensionStates.computeIfAbsent(imageUrl) { mutableStateOf(null) }

    fun persistImageDimensions(imageUrl: String, width: Int, height: Int) {
        if (imageUrl.isBlank() || width <= 0 || height <= 0) return
        val dims = width to height
        // A recycled item re-entering composition re-fires AsyncImage.onSuccess with the same
        // dimensions (memory-cache hits included), so during fast up/down scrolling this is
        // called once per re-entry per image. Skip the whole cascade — snapshot write, Room
        // rewrite, and apply-job churn — when nothing actually changed.
        val state = dimensionStates.computeIfAbsent(imageUrl) { mutableStateOf(null) }
        if (state.value == dims) return
        state.value = dims
        pendingImageDimensions[imageUrl] = dims
        contentDimUpdates[imageUrl] = dims
        scheduleDimensionDbFlush()
        scheduleContentDimApply()
    }

    // Persist resolved dimensions to the on-disk cache promptly (off-main, no UI cost).
    private fun scheduleDimensionDbFlush() {
        if (dimensionFlushJob?.isActive == true) return
        dimensionFlushJob = scope.launch {
            delay(IMAGE_DIMENSION_FLUSH_DELAY_MS)
            while (pendingImageDimensions.isNotEmpty()) {
                if (!flushPendingImageDimensions()) break
            }
        }
    }

    // Trailing debounce for the in-memory content rebuild. Applying dimensions per-image rebuilt
    // the whole chapter (`paragraphs.map{}` + `content.copy`) and re-emitted ui state on every
    // decode, recomposing the reader on the main thread — measured as 9–16ms frame overruns
    // (micro-stutter) at 120Hz during scroll. The live image already sizes itself via
    // ReaderImageView.runtimeDimensions, so the content mutation only needs to land once loading
    // settles (for re-composed items / future restore math). Each new dimension cancels the
    // pending apply, so a continuous scroll never rebuilds content mid-fling.
    private fun scheduleContentDimApply() {
        contentDimApplyJob?.cancel()
        contentDimApplyJob = scope.launch {
            delay(CONTENT_DIM_APPLY_DEBOUNCE_MS)
            if (contentDimUpdates.isEmpty()) return@launch
            val batch = contentDimUpdates.toMap()
            contentDimUpdates.clear()
            applyContentDimensions(batch)
        }
    }

    /** @return false when the write failed; the batch is re-queued for a later flush. */
    private suspend fun flushPendingImageDimensions(): Boolean {
        val updates = pendingImageDimensions.toMap()
        pendingImageDimensions.clear()
        if (updates.isEmpty()) return true
        val persisted = imageDimensionCache.persistAll(updates.map { (url, dimensions) ->
            Triple(url, dimensions.first, dimensions.second)
        })
        if (!persisted) {
            // Re-queue: the duplicate-persist guard means no future onSuccess will re-enqueue
            // these dims itself, so dropping the batch here would lose them for the session.
            // putIfAbsent keeps any newer value that arrived during the failed write.
            updates.forEach { (url, dims) -> pendingImageDimensions.putIfAbsent(url, dims) }
            Log.e(TAG, "Failed to flush pending image dimensions; batch re-queued")
        }
        return persisted
    }

    /**
     * Called after a new chapter's content replaces the old one. Drops per-URL state for images
     * that are not part of the new chapter — without this the manager grows unboundedly for the
     * life of the (Activity-scoped) ReaderViewModel, one entry per image ever displayed. Keeping
     * the new chapter's own urls means a same-chapter reload keeps its dims. The db-flush job /
     * pending map are intentionally left running so an in-flight persist of already-resolved
     * dimensions still completes (the on-disk cache is cross-chapter by design).
     */
    fun pruneForChapter(currentImageUrls: Set<String>) {
        dimensionStates.keys.retainAll(currentImageUrls)
        contentDimUpdates.keys.retainAll(currentImageUrls)
        // Re-enqueue the survivors' resolved dims: a url shared with the previous chapter can
        // hold dimensions here while the new chapter's element still lacks them (the Room seed
        // raced the 100ms flush), and the duplicate-persist guard would otherwise block the
        // content rebuild from ever healing that element.
        dimensionStates.forEach { (url, state) ->
            val dims = state.value ?: return@forEach
            contentDimUpdates.putIfAbsent(url, dims)
        }
        if (contentDimUpdates.isNotEmpty()) scheduleContentDimApply()
    }
}
