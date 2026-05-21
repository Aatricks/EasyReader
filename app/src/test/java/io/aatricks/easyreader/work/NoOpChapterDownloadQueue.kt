package io.aatricks.easyreader.work

import io.aatricks.easyreader.data.model.PrefetchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test-only no-op queue. Existing LibraryViewModel tests rely on the direct in-process
 * prefetch path; this no-op lets them keep mocking ContentRepository.prefetchWithProgress
 * without standing up a Robolectric WorkManager test harness.
 */
class NoOpChapterDownloadQueue : ChapterDownloadQueue {
    override fun enqueue(url: String, replaceExisting: Boolean) = Unit
    override fun cancel(url: String) = Unit
    override fun observeChapter(url: String): Flow<PrefetchResult?> = flowOf(null)
    override fun observeAll(): Flow<Map<String, PrefetchResult>> = flowOf(emptyMap())
}
