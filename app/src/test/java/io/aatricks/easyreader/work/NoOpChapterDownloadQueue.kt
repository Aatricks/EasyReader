package io.aatricks.easyreader.work

import io.aatricks.easyreader.data.model.PrefetchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Test-only queue for ViewModel tests that do not care about download work. */
class NoOpChapterDownloadQueue : ChapterDownloadQueue {
    override fun enqueue(url: String, replaceExisting: Boolean) = Unit
    override fun cancel(url: String) = Unit
    override fun observeChapter(url: String): Flow<PrefetchResult?> = flowOf(null)
    override fun observeAll(): Flow<Map<String, PrefetchResult>> = flowOf(emptyMap())
}
