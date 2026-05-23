package io.aatricks.easyreader.data.repository.content

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-host concurrency + spacing across both image and HTML fetches. A single shared
 * instance means a 429 on the HTML page advances the same throttle the image downloads
 * read from, so backoff applies to every request to that host.
 */
@Singleton
class HostThrottle @Inject constructor() {
    companion object {
        const val SUCCESS_SPACING_MS = 25L
        const val RATE_LIMIT_SPACING_MS = 1200L
        const val NETWORK_ERROR_SPACING_MS = 300L
        const val MAX_RETRY_AFTER_MS = 10_000L
        private const val PER_HOST_CONCURRENCY = 8
        private const val MAX_STATES = 256
    }

    sealed interface Outcome {
        object Success : Outcome
        data class RateLimited(val retryAfterMs: Long?) : Outcome
        object RetryableError : Outcome
        object NetworkError : Outcome
    }

    private data class State(
        val semaphore: Semaphore = Semaphore(PER_HOST_CONCURRENCY),
        val mutex: Mutex = Mutex(),
        var nextAllowedAtMs: Long = 0L
    )

    private val statesMutex = Mutex()
    private val states = object : LinkedHashMap<String, State>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?): Boolean =
            size > MAX_STATES
    }

    suspend fun <T> execute(url: String, block: suspend () -> T, classify: (T) -> Outcome): T {
        val state = stateFor(url)
        return state.semaphore.withPermit {
            waitForSlot(state)
            val result = block()
            recordOutcome(state, classify(result))
            result
        }
    }

    private suspend fun stateFor(url: String): State {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: url
        return statesMutex.withLock { states.getOrPut(host) { State() } }
    }

    private suspend fun waitForSlot(state: State) {
        val waitMs = state.mutex.withLock {
            val now = System.currentTimeMillis()
            val allowed = state.nextAllowedAtMs
            val wait = (allowed - now).coerceAtLeast(0L)
            if (wait > 0) {
                state.nextAllowedAtMs = allowed + SUCCESS_SPACING_MS
            }
            wait
        }
        if (waitMs > 0) delay(waitMs)
    }

    private suspend fun recordOutcome(state: State, outcome: Outcome) {
        val spacing = when (outcome) {
            is Outcome.RateLimited -> outcome.retryAfterMs ?: RATE_LIMIT_SPACING_MS
            Outcome.RetryableError -> RATE_LIMIT_SPACING_MS
            Outcome.NetworkError -> NETWORK_ERROR_SPACING_MS
            Outcome.Success -> SUCCESS_SPACING_MS
        }
        state.mutex.withLock {
            val nextAllowed = System.currentTimeMillis() + spacing
            if (nextAllowed > state.nextAllowedAtMs) state.nextAllowedAtMs = nextAllowed
        }
    }
}
