package io.aatricks.easyreader.ui.screens.library

import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem

private const val PREVIEW_CHAPTER_COUNT = 3

internal sealed interface LibraryRenderItem {
    val key: String

    data class SourceHeader(
        val sourceName: String,
        val isExpanded: Boolean,
        override val key: String = "source_$sourceName"
    ) : LibraryRenderItem

    data class EpubRow(
        val item: LibraryItem,
        override val key: String = "epub_${item.id}"
    ) : LibraryRenderItem

    data class NovelHeader(
        val groupKey: String,
        val title: String,
        val items: List<LibraryItem>,
        val isExpanded: Boolean,
        override val key: String = "novel_$groupKey"
    ) : LibraryRenderItem

    data class NovelResumeButton(
        val groupKey: String,
        val item: LibraryItem,
        override val key: String = "resume_$groupKey"
    ) : LibraryRenderItem

    data class ChapterRow(
        val groupKey: String,
        val item: LibraryItem,
        val currentItemId: String?,
        val isSummaryExpanded: Boolean,
        override val key: String = "chapter_${item.id}"
    ) : LibraryRenderItem

    data class ShowMoreControl(
        val groupKey: String,
        val chapterCount: Int,
        val showFullChapters: Boolean,
        override val key: String = "show_more_$groupKey"
    ) : LibraryRenderItem
}

internal data class LibraryFlattenState(
    val groupedBySource: Map<String, Map<String, List<LibraryItem>>>,
    val collapsedSources: Set<String>,
    val expandedNovels: Map<String, Boolean>,
    val showFullChapters: Map<String, Boolean>,
    val expandedSummaryChapterUrls: Map<String, String?>,
    val isSelectionMode: Boolean = false
)

internal fun flattenLibraryItems(state: LibraryFlattenState): List<LibraryRenderItem> = buildList {
    state.groupedBySource.forEach source@{ (sourceName, novels) ->
        val isSourceExpanded = sourceName !in state.collapsedSources
        add(LibraryRenderItem.SourceHeader(sourceName, isSourceExpanded))
        if (!isSourceExpanded) return@source

        novels.forEach novel@{ (groupTitle, chapterItems) ->
            val firstItem = chapterItems.firstOrNull() ?: return@novel
            if (firstItem.contentType == ContentType.EPUB) {
                add(LibraryRenderItem.EpubRow(firstItem))
                return@novel
            }

            val groupKey = "${sourceName}_$groupTitle"
            val isExpanded = state.expandedNovels[groupKey] == true
            add(LibraryRenderItem.NovelHeader(groupKey, groupTitle, chapterItems, isExpanded))
            if (!isExpanded) return@novel

            val lastRead = chapterItems.find { it.isCurrentlyReading }
                ?: chapterItems.maxByOrNull { it.lastRead }
            if (!state.isSelectionMode && lastRead != null) {
                add(LibraryRenderItem.NovelResumeButton(groupKey, lastRead))
            }

            val visibleChapters = if (state.showFullChapters[groupKey] == true) {
                chapterItems
            } else {
                chapterItems.take(PREVIEW_CHAPTER_COUNT)
            }
            val expandedSummaryUrl = state.expandedSummaryChapterUrls[groupKey]
            visibleChapters.forEach { chapter ->
                val chapterUrl = chapter.currentChapterUrl.ifBlank { chapter.url }
                add(
                    LibraryRenderItem.ChapterRow(
                        groupKey = groupKey,
                        item = chapter,
                        currentItemId = lastRead?.id,
                        isSummaryExpanded = expandedSummaryUrl == chapterUrl
                    )
                )
            }
            if (chapterItems.size > PREVIEW_CHAPTER_COUNT) {
                add(
                    LibraryRenderItem.ShowMoreControl(
                        groupKey = groupKey,
                        chapterCount = chapterItems.size,
                        showFullChapters = state.showFullChapters[groupKey] == true
                    )
                )
            }
        }
    }
}
