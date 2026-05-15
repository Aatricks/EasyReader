package io.aatricks.easyreader.util

import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem

fun computeAutoDeleteCandidates(
    allItems: List<LibraryItem>,
    baseTitle: String,
    currentUrl: String,
    currentChapterNumber: Double
): List<LibraryItem> {
    return allItems
        .asSequence()
        .filter { item ->
            item.baseTitle == baseTitle &&
                item.contentType == ContentType.WEB &&
                item.url != currentUrl &&
                item.progress == 100 &&
                !item.isDownloaded
        }
        .filter { item ->
            val otherNumber = TextUtils.extractChapterNumber(item.currentChapter)
                ?: TextUtils.extractChapterNumber(item.url)
                ?: return@filter false

            (currentChapterNumber - otherNumber) > 1
        }
        .toList()
}
