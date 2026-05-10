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
 *
 * URL parsing is unreliable: numeric book-ID slugs like `/book/12345/epilogue` get extracted as
 * chapter 12345. Use `titleChapterNumber()` for comparisons that must avoid that noise.
 */
fun LibraryItem.resolvedChapterNumber(): Double? {
    return titleChapterNumber()
        ?: currentChapterUrl.takeIf { it.isNotBlank() }?.let(TextUtils::extractChapterNumber)
        ?: TextUtils.extractChapterNumber(url)
}

/**
 * Resolve the chapter number using only `currentChapter` (or fallback to `title`).
 * Skips URL parsing entirely — URLs may contain non-chapter numbers (book IDs, year stamps,
 * pagination slugs) that pollute comparisons.
 */
fun LibraryItem.titleChapterNumber(): Double? {
    return TextUtils.extractChapterNumber(currentChapter.ifBlank { title })
}
