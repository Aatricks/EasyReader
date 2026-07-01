package io.aatricks.easyreader.ui.viewmodel

import androidx.compose.runtime.mutableStateMapOf
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Resolved intrinsic dimensions keyed by image URL, as fine-grained Compose state. A
    // ReaderImageView reads its own entry, so a write only recomposes that one image — and an
    // item scrolled away and back is sized correctly on its FIRST composition (no collapse to the
    // loading placeholder + relayout). This is what keeps fast up/down dragging smooth; the
    // debounced content rebuild stays only for persistence / restore math.
    val resolvedImageDimensions = mutableStateMapOf<String, Pair<Int, Int>>()

    private val pendingImageDimensions = LinkedHashMap<String, Pair<Int, Int>>()
    private val contentDimUpdates = LinkedHashMap<String, Pair<Int, Int>>()
    private var dimensionFlushJob: Job? = null
    private var contentDimApplyJob: Job? = null

    companion object {
        private const val IMAGE_DIMENSION_FLUSH_DELAY_MS = 100L
        private const val CONTENT_DIM_APPLY_DEBOUNCE_MS = 350L
    }

    fun persistImageDimensions(imageUrl: String, width: Int, height: Int) {
        if (imageUrl.isBlank() || width <= 0 || height <= 0) return
        resolvedImageDimensions[imageUrl] = width to height
        pendingImageDimensions[imageUrl] = width to height
        contentDimUpdates[imageUrl] = width to height
        scheduleDimensionDbFlush()
        scheduleContentDimApply()
    }

    // Persist resolved dimensions to the on-disk cache promptly (off-main, no UI cost).
    private fun scheduleDimensionDbFlush() {
        if (dimensionFlushJob?.isActive == true) return
        dimensionFlushJob = scope.launch {
            delay(IMAGE_DIMENSION_FLUSH_DELAY_MS)
            while (pendingImageDimensions.isNotEmpty()) {
                flushPendingImageDimensions()
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

    private suspend fun flushPendingImageDimensions() {
        val updates = pendingImageDimensions.toMap()
        pendingImageDimensions.clear()
        if (updates.isEmpty()) return
        imageDimensionCache.persistAll(updates.map { (url, dimensions) ->
            Triple(url, dimensions.first, dimensions.second)
        })
    }

    // Matches the reader's resetState: drop the queued in-memory rebuild and the resolved map.
    // The db-flush job / pending map are intentionally left running so an in-flight persist of
    // already-resolved dimensions still completes.
    fun reset() {
        contentDimApplyJob?.cancel()
        contentDimUpdates.clear()
        resolvedImageDimensions.clear()
    }
}
