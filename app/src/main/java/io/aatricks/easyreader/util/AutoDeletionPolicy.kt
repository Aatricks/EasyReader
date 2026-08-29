package io.aatricks.easyreader.util

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.hasFinishedProgress
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.data.model.resolvedChapterNumber

/**
 * How many chapters must separate the current chapter from an older one before the older one is
 * eligible for cleanup. `> 1` keeps both the current chapter and the immediately-previous one
 * (n-1) intact.
 */
private const val MIN_CHAPTERS_BEHIND = 1

/**
 * What to clean up for the chapter currently being read. See [computeDownloadCleanup].
 *
 * @property downloadsToFree library rows whose downloaded files should be deleted and whose
 *   `isDownloaded` flag should be cleared. The row itself — and its read progress — is kept.
 * @property speculativeCacheUrls chapter URLs that are NOT in the library (speculative prefetches)
 *   whose cache should be evicted. No library row exists for these, so nothing to keep.
 */
data class DownloadCleanupPlan(
    val downloadsToFree: List<LibraryItem>,
    val speculativeCacheUrls: List<String>
)

/**
 * Single source of truth for reader auto-cleanup decisions.
 *
 * A downloaded chapter is freed when it is in the same series ([baseTitle]), web-sourced, read to
 * the end (>= 90%, [hasFinishedProgress]), and at least two chapters behind the current one. Only
 * its files are deleted; the library row and its progress are preserved so reading history stays
 * intact. Downloaded-ahead (unread) chapters keep `progress = 0` and are never touched.
 *
 * Chapters not in the library but present in [fullChapterList] and far enough behind get their
 * speculative caches evicted so stray prefetches don't accumulate forever.
 *
 * Chapter numbers are resolved via [resolvedChapterNumber] on both sides of the comparison so the
 * whole app shares one numbering scheme; a chapter with no parseable number is skipped.
 */
fun computeDownloadCleanup(
    allItems: List<LibraryItem>,
    fullChapterList: List<ChapterInfo>,
    baseTitle: String,
    currentUrl: String,
    currentChapterNumber: Double
): DownloadCleanupPlan {
    val downloadsToFree = allItems.filter { item ->
        item.libraryDisplayTitle() == baseTitle &&
            item.contentType == ContentType.WEB &&
            !areChapterUrlsMatching(item.url, currentUrl) &&
            item.isDownloaded &&
            item.hasFinishedProgress() &&
            isFarEnoughBehind(currentChapterNumber, item.resolvedChapterNumber())
    }

    val inLibraryUrls = allItems.mapTo(HashSet()) { it.url }
    val speculativeCacheUrls = fullChapterList
        .asSequence()
        .filter { chapter ->
            chapter.url.isNotBlank() &&
                !areChapterUrlsMatching(chapter.url, currentUrl) &&
                inLibraryUrls.none { areChapterUrlsMatching(it, chapter.url) }
        }
        .filter { chapter ->
            val number = chapter.number
                ?: TextUtils.extractChapterNumber(chapter.title)
                ?: TextUtils.extractChapterNumber(chapter.url)
            isFarEnoughBehind(currentChapterNumber, number)
        }
        .map { it.url }
        .toList()

    return DownloadCleanupPlan(downloadsToFree, speculativeCacheUrls)
}

private fun isFarEnoughBehind(currentChapterNumber: Double, otherNumber: Double?): Boolean {
    if (otherNumber == null) return false
    return (currentChapterNumber - otherNumber) > MIN_CHAPTERS_BEHIND
}
