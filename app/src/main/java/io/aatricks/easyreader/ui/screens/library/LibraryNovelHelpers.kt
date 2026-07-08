package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.hasActionableUpdate
import io.aatricks.easyreader.data.model.hasFinishedProgress
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.resolvedChapterNumber
import io.aatricks.easyreader.data.model.seriesReadingStatus

data class DrawerNovelEntry(
    val novelKey: String,
    val displayTitle: String,
    val resumeItem: LibraryItem,
    val updateItem: LibraryItem,
    val hasUpdates: Boolean,
    val isFinished: Boolean,
    val activityTimestamp: Long,
    val updateTimestamp: Long
)

data class DrawerNovelSections(
    val continueNovel: DrawerNovelEntry?,
    val recentUpdates: List<DrawerNovelEntry>,
    val recentNovels: List<DrawerNovelEntry>
)

internal fun countDistinctNovelTitles(items: List<LibraryItem>): Int {
    return items.asSequence()
        .map { it.libraryNovelKey() }
        .distinct()
        .count()
}

internal fun buildDrawerNovelSections(items: List<LibraryItem>): DrawerNovelSections {
    val novels = items.groupBy { it.libraryNovelKey() }
        .values
        .map(::buildDrawerNovelEntry)

    val continueNovel = novels.find { it.resumeItem.isCurrentlyReading }
        ?: novels.maxByOrNull { it.activityTimestamp }

    val recentUpdates = novels
        .asSequence()
        .filter { it.hasUpdates }
        .filterNot { it.novelKey == continueNovel?.novelKey }
        .sortedByDescending { it.updateTimestamp }
        .take(4)
        .toList()

    val recentUpdateKeys = recentUpdates.map { it.novelKey }.toSet()

    val recentNovels = novels
        .asSequence()
        .filterNot { it.isFinished }
        .filterNot { it.hasUpdates }
        .filterNot { it.novelKey == continueNovel?.novelKey }
        .filterNot { it.novelKey in recentUpdateKeys }
        .sortedByDescending { it.activityTimestamp }
        .take(6)
        .toList()

    return DrawerNovelSections(
        continueNovel = continueNovel,
        recentUpdates = recentUpdates,
        recentNovels = recentNovels
    )
}

/**
 * Convenience wrapper: a single-item view of "is this series finished" used by legacy callers.
 * Now delegates to the unified [seriesReadingStatus].
 */
internal fun isNovelFinished(item: LibraryItem, latestKnownChapterCount: Int): Boolean {
    if (!item.hasFinishedProgress()) return false
    val currentChapterNumber = item.resolvedChapterNumber() ?: return false
    return latestKnownChapterCount > 0 && currentChapterNumber >= latestKnownChapterCount.toDouble()
}

internal fun latestLibraryUpdateItem(items: List<LibraryItem>): LibraryItem? {
    return items
        .asSequence()
        .filter { it.hasActionableUpdate() }
        .filter { it.baseNovelUrl.isNotBlank() || it.sourceName.isNotBlank() }
        .maxByOrNull { it.dateAdded }
        ?: items
            .asSequence()
            .filter { it.hasActionableUpdate() }
            .maxByOrNull { it.dateAdded }
}

private fun buildDrawerNovelEntry(items: List<LibraryItem>): DrawerNovelEntry {
    val fallbackItem = items.first()
    val resumeItem = items.find { it.isCurrentlyReading }
        ?: items.maxByOrNull { it.lastRead }
        ?: items.maxByOrNull { it.dateAdded }
        ?: fallbackItem
    val updateItem = latestLibraryUpdateItem(items)
        ?: items
            .filter { it.baseNovelUrl.isNotBlank() || it.sourceName.isNotBlank() }
            .maxByOrNull { it.dateAdded }
        ?: items.maxByOrNull { it.dateAdded }
        ?: fallbackItem
    val hasUpdates = latestLibraryUpdateItem(items) != null
    val isFinished = seriesReadingStatus(items) == SeriesReadingStatus.FINISHED

    return DrawerNovelEntry(
        novelKey = fallbackItem.libraryNovelKey(),
        displayTitle = fallbackItem.libraryDisplayTitle(),
        resumeItem = resumeItem,
        updateItem = updateItem,
        hasUpdates = hasUpdates,
        isFinished = isFinished,
        activityTimestamp = items.maxOfOrNull { maxOf(it.lastRead, it.dateAdded) } ?: 0L,
        updateTimestamp = items.filter { it.hasActionableUpdate() }.maxOfOrNull { it.dateAdded } ?: Long.MIN_VALUE
    )
}

internal fun getLibraryItemResumeLabel(item: LibraryItem): String {
    return if (item.progress == 0 && item.currentChapterUrl.isBlank()) {
        "Start reading"
    } else {
        "Resume ${item.currentChapter.ifBlank { "Chapter 1" }}"
    }
}

