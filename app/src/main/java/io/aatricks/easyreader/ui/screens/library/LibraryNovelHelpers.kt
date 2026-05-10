package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.LIBRARY_FINISHED_CHAPTER_TOLERANCE
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.hasActionableUpdate
import io.aatricks.easyreader.data.model.hasFinishedProgress
import io.aatricks.easyreader.data.model.resolvedChapterNumber

internal data class DrawerNovelEntry(
    val novelKey: String,
    val displayTitle: String,
    val resumeItem: LibraryItem,
    val updateItem: LibraryItem,
    val hasUpdates: Boolean,
    val isFinished: Boolean,
    val activityTimestamp: Long,
    val updateTimestamp: Long
)

internal data class DrawerNovelSections(
    val continueNovel: DrawerNovelEntry?,
    val recentUpdates: List<DrawerNovelEntry>,
    val recentNovels: List<DrawerNovelEntry>
)

internal fun libraryNovelKey(item: LibraryItem): String {
    val sourceKey = item.sourceName.ifBlank { item.contentType.name }
    return "$sourceKey::${libraryNovelDisplayTitle(item)}"
}

internal fun countDistinctNovelTitles(items: List<LibraryItem>): Int {
    return items.asSequence()
        .map(::libraryNovelKey)
        .distinct()
        .count()
}

internal fun buildDrawerNovelSections(items: List<LibraryItem>): DrawerNovelSections {
    val novels = items.groupBy(::libraryNovelKey)
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
    val latestKnownChapterCount = items.maxOfOrNull { it.totalChapters } ?: 0
    val hasUpdates = latestLibraryUpdateItem(items) != null
    val highestChapterItem = items
        .mapNotNull { item -> item.resolvedChapterNumber()?.let { num -> num to item } }
        .maxByOrNull { (num, _) -> num }
    val isFinished = highestChapterItem?.let { (highestChapterNumber, highestItem) ->
        if (!highestItem.hasFinishedProgress()) {
            false
        } else if (latestKnownChapterCount > 0 && highestChapterNumber >= latestKnownChapterCount.toDouble()) {
            true
        } else if (!hasUpdates) {
            latestKnownChapterCount <= 0 ||
                latestKnownChapterCount.toDouble() - highestChapterNumber <= LIBRARY_FINISHED_CHAPTER_TOLERANCE
        } else {
            false
        }
    } ?: false

    return DrawerNovelEntry(
        novelKey = libraryNovelKey(fallbackItem),
        displayTitle = libraryNovelDisplayTitle(fallbackItem),
        resumeItem = resumeItem,
        updateItem = updateItem,
        hasUpdates = hasUpdates,
        isFinished = isFinished,
        activityTimestamp = items.maxOfOrNull { maxOf(it.lastRead, it.dateAdded) } ?: 0L,
        updateTimestamp = items.filter { it.hasActionableUpdate() }.maxOfOrNull { it.dateAdded } ?: Long.MIN_VALUE
    )
}

private fun libraryNovelDisplayTitle(item: LibraryItem): String = item.baseTitle.ifBlank { item.title }
