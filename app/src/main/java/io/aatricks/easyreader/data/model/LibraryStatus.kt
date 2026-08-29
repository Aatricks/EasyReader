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

/**
 * Display title used for series grouping and labels.
 */
fun LibraryItem.libraryDisplayTitle(): String =
    baseTitle.ifBlank { TextUtils.extractBaseTitle(title, contentType) }

/**
 * Stable per-series key used for grouping chapter rows into a series.
 *
 * Combines source (or content-type fallback) with the display title so that a novel imported
 * from two different sources stays separated even if titles match exactly.
 */
fun LibraryItem.libraryNovelKey(): String {
    val sourceKey = sourceName.ifBlank { contentType.name }
    return "$sourceKey::${libraryDisplayTitle()}"
}

enum class SeriesReadingStatus(val label: String) {
    ALL("All"),
    READING("Reading"),
    FINISHED("Finished"),
    UNREAD("Not started")
}

/**
 * Determine series-level reading status from its chapter rows. Single source of truth used by
 * the Library status filter chips, the navigation drawer's "finished" hiding, and any other
 * place that needs series-level read state.
 *
 * Trust the user's library state: the row with the highest title-derived chapter number is
 * "the last chapter the user has". If that row is read (>= 90%), the series is FINISHED.
 *
 * - Multi-chapter library: highest title-numbered row decides. URL-derived numbers are NOT
 *   used for the highest comparison — they pick up book-ID slugs and pollute results.
 * - Single-chapter library: only FINISHED if the chapter equals (or is within tolerance of)
 *   the source's totalChapters. Prevents brand-new "chapter 1 of 250" reads from showing as
 *   finished.
 * - No parseable chapter numbers anywhere: fall back to "any finished item" → FINISHED.
 * - Source `totalChapters` and `hasUpdates` flags are ignored for multi-chapter libraries.
 *   Sources lie (specials, extras, removed chapters); the user knows what's in their library.
 */
fun seriesReadingStatus(items: List<LibraryItem>): SeriesReadingStatus {
    if (items.isEmpty()) return SeriesReadingStatus.UNREAD

    val anyStarted = items.any { it.progress > 0 }

    if (items.size == 1) {
        val item = items.single()
        if (!item.hasFinishedProgress()) {
            return if (item.progress > 0) SeriesReadingStatus.READING
            else SeriesReadingStatus.UNREAD
        }
        val number = item.titleChapterNumber() ?: item.resolvedChapterNumber()
        val total = item.totalChapters
        val isFinishedSingle = when {
            total <= 0 -> true
            number == null -> true
            number >= total.toDouble() - LIBRARY_FINISHED_CHAPTER_TOLERANCE -> true
            else -> false
        }
        return if (isFinishedSingle) SeriesReadingStatus.FINISHED
        else SeriesReadingStatus.READING
    }

    val titleNumbered = items.mapNotNull { item ->
        item.titleChapterNumber()?.let { num -> num to item }
    }
    val highest = titleNumbered.maxByOrNull { (num, _) -> num }

    if (highest == null) {
        return when {
            items.any { it.hasFinishedProgress() } -> SeriesReadingStatus.FINISHED
            anyStarted -> SeriesReadingStatus.READING
            else -> SeriesReadingStatus.UNREAD
        }
    }

    val (_, highestItem) = highest
    return when {
        highestItem.hasFinishedProgress() -> SeriesReadingStatus.FINISHED
        anyStarted -> SeriesReadingStatus.READING
        else -> SeriesReadingStatus.UNREAD
    }
}
