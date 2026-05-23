package io.aatricks.easyreader.data.model

import androidx.room.Entity
import androidx.room.Index

/**
 * Per-image bookkeeping for chapter downloads. Replaces the on-disk `.failed` sidecar so
 * permanent-failure state survives cache eviction and is queryable as a set rather than a
 * per-chapter file read. Status values:
 *
 *  - `PERMANENT_FAILURE` — fetcher classified the URL as a 4xx-style dead URL (see HttpRetry).
 *    Counts toward `isComplete` (loop has nothing more to attempt) but the image is not on
 *    disk; surfaced as `hasPermanentFailures` so the UI/DB-flag won't claim "Downloaded".
 *    Entries have a TTL so a transient CDN 4xx auto-recovers after a day.
 *
 * The on-disk image file is still the source of truth for "downloaded"; this table only
 * tracks what we've given up retrying.
 */
@Entity(
    tableName = "chapter_image_state",
    primaryKeys = ["chapterUrl", "imageUrl"],
    indices = [
        Index(value = ["chapterUrl"]),
        Index(value = ["status"])
    ]
)
data class ChapterImageStateEntity(
    val chapterUrl: String,
    val imageUrl: String,
    val status: String,
    val attempts: Int = 0,
    val lastAttemptMs: Long = 0L,
    val httpStatusCode: Int? = null
) {
    companion object {
        const val STATUS_PERMANENT_FAILURE = "PERMANENT_FAILURE"
    }
}
