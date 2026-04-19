package io.aatricks.novelscraper.ui.screens

import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.util.TextUtils

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
    if (latestKnownChapterCount <= 0 || item.progress < 100) return false

    val currentChapterNumber = extractLibraryChapterNumber(item) ?: return false
    return currentChapterNumber >= latestKnownChapterCount.toDouble()
}

private fun buildDrawerNovelEntry(items: List<LibraryItem>): DrawerNovelEntry {
    val fallbackItem = items.first()
    val resumeItem = items.find { it.isCurrentlyReading }
        ?: items.maxByOrNull { it.lastRead }
        ?: items.maxByOrNull { it.dateAdded }
        ?: fallbackItem
    val updateItem = items
        .filter { it.baseNovelUrl.isNotBlank() || it.sourceName.isNotBlank() }
        .maxByOrNull { it.dateAdded }
        ?: items.maxByOrNull { it.dateAdded }
        ?: fallbackItem
    val latestKnownChapterCount = items.maxOfOrNull { it.totalChapters } ?: 0
    val isFinished = items.any { isNovelFinished(it, latestKnownChapterCount) }

    return DrawerNovelEntry(
        novelKey = libraryNovelKey(fallbackItem),
        displayTitle = libraryNovelDisplayTitle(fallbackItem),
        resumeItem = resumeItem,
        updateItem = updateItem,
        hasUpdates = items.any { it.hasUpdates },
        isFinished = isFinished,
        activityTimestamp = items.maxOfOrNull { maxOf(it.lastRead, it.dateAdded) } ?: 0L,
        updateTimestamp = items.filter { it.hasUpdates }.maxOfOrNull { it.dateAdded } ?: Long.MIN_VALUE
    )
}

private fun libraryNovelDisplayTitle(item: LibraryItem): String = item.baseTitle.ifBlank { item.title }

private fun extractLibraryChapterNumber(item: LibraryItem): Double? {
    return TextUtils.extractChapterNumber(item.currentChapter.ifBlank { item.title })
        ?: item.currentChapterUrl.takeIf { it.isNotBlank() }?.let(TextUtils::extractChapterNumber)
        ?: TextUtils.extractChapterNumber(item.url)
}
