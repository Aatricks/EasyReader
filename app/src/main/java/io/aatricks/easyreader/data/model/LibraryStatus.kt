package io.aatricks.easyreader.data.model

import io.aatricks.easyreader.util.TextUtils

const val LIBRARY_FINISHED_PROGRESS_THRESHOLD = 90

/**
 * Tolerance (in chapters) when no authoritative `totalChapters` is known.
 * Treat a series as finished if (highestKnownTotal - highestReadChapter) <= this many missing chapters.
 */
const val LIBRARY_FINISHED_CHAPTER_TOLERANCE = 2

fun LibraryItem.hasFinishedProgress(): Boolean =
    progress >= LIBRARY_FINISHED_PROGRESS_THRESHOLD

fun LibraryItem.hasActionableUpdate(): Boolean =
    hasUpdates && hasFinishedProgress()

/**
 * Resolve the chapter number for an item, falling back through every reliable signal.
 *
 * Order:
 * 1. `currentChapter` (or item title if blank — chapter rows are titled like "Novel - Chapter 42").
 * 2. `currentChapterUrl` — progress writes update this even when the chapter label stays blank.
 * 3. `url` — original chapter URL.
 */
fun LibraryItem.resolvedChapterNumber(): Double? {
    return TextUtils.extractChapterNumber(currentChapter.ifBlank { title })
        ?: currentChapterUrl.takeIf { it.isNotBlank() }?.let(TextUtils::extractChapterNumber)
        ?: TextUtils.extractChapterNumber(url)
}
