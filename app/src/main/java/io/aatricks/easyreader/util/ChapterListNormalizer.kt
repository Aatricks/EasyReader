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

internal fun normalizeExploreItemDetails(item: ExploreItem): ExploreItem {
    val normalizedChapters = normalizeChapterList(item.chapters)
    val normalizedReadingUrl = item.readingUrl?.trim().takeUnless { it.isNullOrBlank() }

    return item.copy(
        chapterCount = if (normalizedChapters.isNotEmpty()) normalizedChapters.size else item.chapterCount,
        readingUrl = normalizedReadingUrl ?: normalizedChapters.firstOrNull()?.url,
        chapters = normalizedChapters
    )
}
