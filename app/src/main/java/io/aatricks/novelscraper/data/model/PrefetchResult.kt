package io.aatricks.novelscraper.data.model

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
    val isRetryable: Boolean = true
)
