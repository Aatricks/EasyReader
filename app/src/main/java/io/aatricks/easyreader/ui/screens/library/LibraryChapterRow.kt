package io.aatricks.easyreader.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.ui.components.ChapterSummaryDropdown
import io.aatricks.easyreader.ui.screens.LibraryRenderContext
import io.aatricks.easyreader.ui.screens.openLibraryChapter
import io.aatricks.easyreader.ui.theme.EasyReaderMotion
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import kotlinx.coroutines.launch

private const val SELECTED_ROW_ALPHA = 0.26f
private const val CURRENT_ROW_ALPHA = 0.16f
private const val DOWNLOADED_ICON_SIZE_DP = 14
private const val REMOVE_DOWNLOAD_ICON_SIZE_DP = 18

private data class ChapterRowPresentation(
    val renderItem: LibraryRenderItem.ChapterRow,
    val chapterUrl: String,
    val isSelected: Boolean,
    val isCurrent: Boolean
) {
    val item: LibraryItem = renderItem.item
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun selectableClickBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    Box(
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) {
                Modifier.combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = {
                        if (onLongClick != null) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick.invoke()
                        }
                    }
                )
            } else {
                Modifier
            }
        )
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun novelChapterRow(renderItem: LibraryRenderItem.ChapterRow, context: LibraryRenderContext) {
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
    val presentation = ChapterRowPresentation(
        renderItem = renderItem,
        chapterUrl = renderItem.item.currentChapterUrl.ifBlank { renderItem.item.url },
        isSelected = renderItem.item.id in context.uiState.selectedIds,
        isCurrent = renderItem.item.id == renderItem.currentItemId
    )
    val rowColor by animateColorAsState(
        targetValue = chapterRowColor(presentation),
        animationSpec = tween(EasyReaderMotion.short),
        label = "chapterRowColor"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        chapterRowSurface(presentation, rowColor, context)
        chapterSummary(presentation, context) {
            scope.launch { generateChapterSummary(presentation, context) }
        }
    }
}

@Composable
private fun chapterRowColor(presentation: ChapterRowPresentation): Color = when {
    presentation.isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_ROW_ALPHA)
    presentation.isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = CURRENT_ROW_ALPHA)
    else -> Color.Transparent
}

@Composable
private fun chapterRowSurface(
    presentation: ChapterRowPresentation,
    rowColor: Color,
    context: LibraryRenderContext
) {
    selectableClickBox(
        modifier = Modifier.fillMaxWidth(),
        onClick = { openLibraryChapter(presentation.item, context) },
        onLongClick = { context.libraryViewModel.toggleSelection(presentation.item.id) }
    ) {
        Surface(modifier = Modifier.fillMaxWidth(), color = rowColor, shape = MaterialTheme.shapes.medium) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(EasyReaderSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                if (context.uiState.isSelectionMode) {
                    Checkbox(
                        checked = presentation.isSelected,
                        onCheckedChange = { openLibraryChapter(presentation.item, context) }
                    )
                }
                chapterRowTitle(presentation, Modifier.weight(1f))
                chapterRowActions(presentation, context)
            }
        }
    }
}

@Composable
private fun chapterRowTitle(presentation: ChapterRowPresentation, modifier: Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (presentation.item.isDownloaded) {
                Icon(
                    imageVector = Icons.Default.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(DOWNLOADED_ICON_SIZE_DP.dp)
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xxs))
            }
            Text(
                text = presentation.item.currentChapter.ifBlank { "Chapter 1" },
                color = when {
                    presentation.isSelected -> MaterialTheme.colorScheme.primary
                    presentation.isCurrent -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (presentation.isCurrent) {
            Text(
                "Resume here",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun chapterRowActions(presentation: ChapterRowPresentation, context: LibraryRenderContext) {
    if (context.uiState.isSelectionMode) return
    if (presentation.item.isDownloaded) {
        IconButton(
            onClick = { context.libraryViewModel.removeDownload(presentation.item.id) },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = "Remove download",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(REMOVE_DOWNLOAD_ICON_SIZE_DP.dp)
            )
        }
    }
    TextButton(onClick = {
        context.expandedSummaries[presentation.renderItem.groupKey] =
            if (presentation.renderItem.isSummaryExpanded) null else presentation.chapterUrl
    }) {
        Text(if (presentation.renderItem.isSummaryExpanded) "Hide summary" else "Chapter summary")
    }
}

@Composable
private fun chapterSummary(
    presentation: ChapterRowPresentation,
    context: LibraryRenderContext,
    onGenerate: () -> Unit
) = with(context.summaryUiState) {
    val cachedSummary = presentation.item.chapterSummaries[presentation.chapterUrl]
    val displayedSummary = if (activeChapterUrl == presentation.chapterUrl) currentSummary else cachedSummary
    AnimatedVisibility(
        visible = presentation.renderItem.isSummaryExpanded,
        enter = expandVertically(animationSpec = tween(EasyReaderMotion.medium)) +
            fadeIn(animationSpec = tween(EasyReaderMotion.short)),
        exit = shrinkVertically(animationSpec = tween(EasyReaderMotion.short)) +
            fadeOut(animationSpec = tween(EasyReaderMotion.short))
    ) {
        ChapterSummaryDropdown(
            summary = displayedSummary,
            isGenerating = isGenerating && activeChapterUrl == presentation.chapterUrl,
            aiSupportedInBuild = supportsAi,
            aiOptedIn = isEnabled,
            onEnableAi = { context.summaryViewModel.setAiSummaryEnabled(true) },
            isInitializing = isInitializing,
            isReady = context.summaryViewModel.isServiceReady(),
            onGenerateSummary = onGenerate,
            onCancel = { context.summaryViewModel.cancelGeneration() },
            modifier = Modifier.padding(top = EasyReaderSpacing.xxs)
        )
    }
}

private suspend fun generateChapterSummary(
    presentation: ChapterRowPresentation,
    context: LibraryRenderContext
) {
    val result = context.readerViewModel.contentRepository.loadContent(presentation.chapterUrl)
    if (result !is ContentResult.Success) return
    context.summaryViewModel.generateSummary(
        chapterUrl = presentation.chapterUrl,
        chapterTitle = presentation.item.currentChapter.ifBlank { presentation.item.title },
        content = result.elements.filterIsInstance<ContentElement.Text>().map { it.content }
    ) { summary ->
        val summaries = presentation.item.chapterSummaries.toMutableMap()
        summaries[presentation.chapterUrl] = summary
        context.libraryViewModel.updateItem(presentation.item.copy(chapterSummaries = summaries))
    }
}
