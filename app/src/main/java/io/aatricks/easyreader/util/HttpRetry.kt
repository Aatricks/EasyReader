package io.aatricks.easyreader.util

import kotlin.math.absoluteValue

object HttpRetry {
    const val DEFAULT_MAX_RETRY_AFTER_MS = 10_000L

    fun shouldRetryResponseCode(code: Int): Boolean =
        code == 408 || code == 429 || code in listOf(500, 502, 503, 504)

    fun parseRetryAfterMs(value: String?, maxMs: Long = DEFAULT_MAX_RETRY_AFTER_MS): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return (seconds.coerceAtLeast(1L) * 1000L).coerceAtMost(maxMs)
    }

    fun nextRetryDelayMs(
        retryAfterMs: Long?,
        key: String,
        attempt: Int
    ): Long {
        if (retryAfterMs != null) return retryAfterMs
        val baseDelay = 400L * (1L shl attempt.coerceAtMost(3))
        val jitter = (key.hashCode().toLong().absoluteValue % 180L)
        return baseDelay + jitter
    }
}
