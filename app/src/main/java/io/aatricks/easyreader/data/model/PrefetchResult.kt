package io.aatricks.easyreader.data.model

enum class PrefetchMode {
    USER_REQUESTED,
    SPECULATIVE
}

data class PrefetchResult(
    val url: String,
    val htmlCached: Boolean,
    val totalImages: Int,
    val cachedImages: Int,
    val isComplete: Boolean,
    val isInProgress: Boolean = false,
    val isRetryable: Boolean = true,
    val isPersistentDownload: Boolean = false,
    // True when some images were accounted as permanently failed (in the .failed sidecar)
    // rather than actually on disk. isComplete can still be true (no further work to attempt)
    // but the chapter is not fully present — opening it will show "Image unavailable" for the
    // missing images. The DB isDownloaded flag and the "Downloaded" UI label should only
    // appear when isComplete && !hasPermanentFailures.
    val hasPermanentFailures: Boolean = false
)

fun PrefetchResult.isStrictOfflineReady(): Boolean =
    isPersistentDownload && isComplete && !hasPermanentFailures
