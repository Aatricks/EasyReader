package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.local.ChapterImageStateDao
import io.aatricks.easyreader.data.model.ChapterImageStateEntity
import io.aatricks.easyreader.data.model.ChapterImageStateEntity.Companion.STATUS_PERMANENT_FAILURE
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-image permanent failure state for chapter downloads. Replaces the on-disk
 * `.failed` sidecar so state survives cache eviction. TTL filtering happens at read time
 * so a CDN that returned a transient 4xx can be retried after the freshness window.
 */
interface PermanentFailureStore {
    suspend fun load(chapterUrl: String, freshAfterMs: Long): Set<String>
    suspend fun record(chapterUrl: String, imageUrls: Collection<String>, recordedAtMs: Long)
    suspend fun clear(chapterUrl: String)

    companion object {
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

@Singleton
class RoomPermanentFailureStore @Inject constructor(
    private val dao: ChapterImageStateDao
) : PermanentFailureStore {

    override suspend fun load(chapterUrl: String, freshAfterMs: Long): Set<String> {
        return dao.getActiveImagesByStatus(chapterUrl, STATUS_PERMANENT_FAILURE, freshAfterMs).toSet()
    }

    override suspend fun record(chapterUrl: String, imageUrls: Collection<String>, recordedAtMs: Long) {
        if (imageUrls.isEmpty()) return
        val entities = imageUrls.distinct().map { imageUrl ->
            ChapterImageStateEntity(
                chapterUrl = chapterUrl,
                imageUrl = imageUrl,
                status = STATUS_PERMANENT_FAILURE,
                attempts = 1,
                lastAttemptMs = recordedAtMs
            )
        }
        dao.upsertAll(entities)
    }

    override suspend fun clear(chapterUrl: String) {
        dao.deleteForChapterByStatus(chapterUrl, STATUS_PERMANENT_FAILURE)
    }
}
