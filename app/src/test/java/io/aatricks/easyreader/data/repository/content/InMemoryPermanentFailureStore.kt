package io.aatricks.easyreader.data.repository.content

import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only in-memory implementation of [PermanentFailureStore]. Mirrors the timestamp +
 * TTL semantics of the Room-backed store without the Robolectric dependency.
 */
class InMemoryPermanentFailureStore : PermanentFailureStore {
    private data class Entry(val recordedAtMs: Long)
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()

    override suspend fun load(chapterUrl: String, freshAfterMs: Long): Set<String> {
        val perChapter = data[chapterUrl] ?: return emptySet()
        return perChapter.asSequence()
            .filter { (_, entry) -> entry.recordedAtMs > freshAfterMs }
            .map { it.key }
            .toSet()
    }

    override suspend fun record(chapterUrl: String, imageUrls: Collection<String>, recordedAtMs: Long) {
        if (imageUrls.isEmpty()) return
        val bucket = data.getOrPut(chapterUrl) { ConcurrentHashMap() }
        for (imageUrl in imageUrls) {
            bucket[imageUrl] = Entry(recordedAtMs)
        }
    }

    override suspend fun clear(chapterUrl: String) {
        data.remove(chapterUrl)
    }
}
