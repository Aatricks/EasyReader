package io.aatricks.easyreader.util

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ExploreItem

internal fun normalizeChapterList(chapters: List<ChapterInfo>): List<ChapterInfo> {
    return chapters.asSequence()
        .mapNotNull { chapter ->
            val normalizedUrl = chapter.url.trim()
            if (normalizedUrl.isBlank()) {
                null
            } else {
                chapter.copy(
                    title = chapter.title.trim(),
                    url = normalizedUrl
                )
            }
        }
        .distinctBy { it.url }
        .toList()
}

/**
 * The authoritative chapter label is the one parsed from the source's chapter list
 * (e.g. "Chapter 102 - ..."), not one guessed from the reading URL: Novelight's
 * `/book/chapter/{id}` URLs carry an opaque id, so URL-derived numbering shows the id instead of
 * the chapter number. Returns the matching chapter's list title only when that list entry has a
 * definite number that differs from [currentLabel]'s — a no-op for sources whose URL already yields
 * the right number, and null when the url isn't in the (possibly not-yet-loaded) list so the caller
 * keeps its own label.
 */
internal fun resolveChapterLabelFromList(
    url: String,
    currentLabel: String,
    chapters: List<ChapterInfo>
): String? {
    val target = url.trim()
    val match = if (target.isBlank()) null else chapters.firstOrNull { it.url == target }
    val listNumber = match?.let { it.number ?: TextUtils.extractChapterNumber(it.title) }
    val differs = listNumber != null && TextUtils.extractChapterNumber(currentLabel) != listNumber
    return match?.title?.trim()?.takeIf { it.isNotBlank() && differs }
}

internal fun normalizeExploreItemDetails(item: ExploreItem): ExploreItem {
    val normalizedChapters = normalizeChapterList(item.chapters)
    val normalizedReadingUrl = item.readingUrl?.trim().takeUnless { it.isNullOrBlank() }

    return item.copy(
        chapterCount = if (normalizedChapters.isNotEmpty()) normalizedChapters.size else item.chapterCount,
        readingUrl = normalizedReadingUrl ?: normalizedChapters.firstOrNull()?.url,
        chapters = normalizedChapters
    )
}
