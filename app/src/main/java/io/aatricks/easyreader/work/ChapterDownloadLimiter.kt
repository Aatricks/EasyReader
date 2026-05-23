package io.aatricks.easyreader.work

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object ChapterDownloadLimiter {
    private val semaphore = Semaphore(MAX_ACTIVE_CHAPTER_DOWNLOADS)

    suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }

    private const val MAX_ACTIVE_CHAPTER_DOWNLOADS = 2
}
