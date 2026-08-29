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

internal fun normalizeChapterUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank() || trimmed.startsWith("file://") || trimmed.startsWith("content://")) {
        return trimmed
    }
    return runCatching {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme?.lowercase() ?: "https"
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: ""
        val path = uri.path?.trimEnd('/') ?: ""
        if (host.isNotBlank()) "$scheme://$host$path" else trimmed.trimEnd('/')
    }.getOrDefault(trimmed.trimEnd('/'))
}

internal fun areChapterUrlsMatching(url1: String?, url2: String?): Boolean {
    if (url1.isNullOrBlank() || url2.isNullOrBlank()) return false
    val norm1 = normalizeChapterUrl(url1)
    val norm2 = normalizeChapterUrl(url2)
    val noScheme1 = norm1.substringAfter("://")
    val noScheme2 = norm2.substringAfter("://")
    return url1 == url2 || norm1 == norm2 || (noScheme1.isNotBlank() && noScheme1 == noScheme2)
}

internal fun matchChapterIndex(chapters: List<ChapterInfo>, targetUrl: String?, targetTitle: String? = null): Int {
    if (chapters.isEmpty() || (targetUrl.isNullOrBlank() && targetTitle.isNullOrBlank())) return -1
    val exactIndex = targetUrl?.takeIf { it.isNotBlank() }?.let { url ->
        chapters.indexOfFirst { areChapterUrlsMatching(it.url, url) }
    } ?: -1

    val targetNumber = (targetTitle ?: targetUrl)?.let { TextUtils.extractChapterNumber(it) }
    return when {
        exactIndex >= 0 -> exactIndex
        targetNumber != null -> chapters.indexOfFirst {
            it.number == targetNumber ||
                TextUtils.extractChapterNumber(it.title) == targetNumber ||
                TextUtils.extractChapterNumber(it.url) == targetNumber
        }
        else -> -1
    }
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
    val match = if (target.isBlank()) null else chapters.firstOrNull { areChapterUrlsMatching(it.url, target) }
    val listNumber = match?.let { it.number ?: TextUtils.extractChapterNumber(it.title) }
    val differs = listNumber != null && TextUtils.extractChapterNumber(currentLabel) != listNumber
    return match?.title?.trim()?.takeIf { it.isNotBlank() && differs }
}

private fun formatChapterNum(n: Double): String =
    if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

/**
 * Heal a stored `currentChapter` label whose number came from a Novelight `/book/chapter/{id}` URL
 * (the id, not the chapter number). Given the current reading [currentUrl] and the authoritative
 * [chapters] list, swap the label's number for the list's real one, preserving whatever prefix the
 * label uses ("Chapter 141313" -> "Chapter 102", "141313" -> "102"). Returns null when the url isn't
 * in the list or the number is already correct, so the caller leaves the stored value untouched.
 */
internal fun healCurrentChapterLabel(
    currentChapter: String,
    currentUrl: String?,
    chapters: List<ChapterInfo>
): String? {
    val listNumber = currentUrl
        ?.let { url -> chapters.firstOrNull { areChapterUrlsMatching(it.url, url) } }
        ?.number
    val currentNumber = TextUtils.extractChapterNumber(currentChapter)
    if (listNumber == null || currentNumber == listNumber) return null
    val listStr = formatChapterNum(listNumber)
    return currentNumber
        ?.let { currentChapter.replaceFirst(formatChapterNum(it), listStr) }
        ?: "Chapter $listStr"
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
