package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.ui.components.ChapterSummaryDropdown
import io.aatricks.easyreader.ui.theme.EasyReaderMotion
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.SummaryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryItemList(
    uiState: LibraryViewModel.LibraryUiState,
    readerUiState: ReaderViewModel.ReaderUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    summaryViewModel: SummaryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    val expandedNovelState = remember { mutableStateMapOf<String, Boolean>() }
    val showFullChaptersState = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = EasyReaderSpacing.xs, bottom = EasyReaderSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
    ) {
        uiState.groupedBySource.forEach { (sourceName, novels) ->
            val isSourceExpanded = !uiState.collapsedSources.contains(sourceName)

            item(key = "source_$sourceName") {
                SourceHeader(
                    name = sourceName,
                    isExpanded = isSourceExpanded,
                    onClick = { libraryViewModel.toggleSourceExpansion(sourceName) }
                )
            }

            if (isSourceExpanded) {
                novels.forEach { (groupTitle, chapterItems) ->
                    val groupKey = "${sourceName}_$groupTitle"

                    item(key = groupKey) {
                        val firstItem = chapterItems.firstOrNull()
                        if (firstItem?.contentType == ContentType.EPUB) {
                            EpubItemCard(
                                item = firstItem,
                                uiState = uiState,
                                contentRepository = readerViewModel.contentRepository,
                                readerViewModel = readerViewModel,
                                libraryViewModel = libraryViewModel,
                                onCloseLibrary = onCloseLibrary
                            )
                        } else {
                            NovelGroupCard(
                                title = groupTitle,
                                items = chapterItems,
                                uiState = uiState,
                                readerUiState = readerUiState,
                                summaryUiState = summaryUiState,
                                isExpanded = expandedNovelState.getOrPut(groupKey) { false },
                                showFullChapters = showFullChaptersState[groupKey] ?: false,
                                onToggleExpand = {
                                    expandedNovelState[groupKey] = !(expandedNovelState[groupKey] ?: false)
                                },
                                onToggleShowFull = {
                                    showFullChaptersState[groupKey] = !(showFullChaptersState[groupKey] ?: false)
                                },
                                libraryViewModel = libraryViewModel,
                                readerViewModel = readerViewModel,
                                summaryViewModel = summaryViewModel,
                                onCloseLibrary = onCloseLibrary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceHeader(
    name: String,
    isExpanded: Boolean,
    onClick: () -> Unit
): Unit {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = EasyReaderSpacing.xs, horizontal = EasyReaderSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelGroupCard(
    title: String,
    items: List<LibraryItem>,
    uiState: LibraryViewModel.LibraryUiState,
    readerUiState: ReaderViewModel.ReaderUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    isExpanded: Boolean,
    showFullChapters: Boolean,
    onToggleExpand: () -> Unit,
    onToggleShowFull: () -> Unit,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    summaryViewModel: SummaryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    val isGroupSelected = items.all { it.id in uiState.selectedIds }
    val hasGroupSelection = items.any { it.id in uiState.selectedIds }
    var expandedSummaryChapterUrl by remember(title, items.firstOrNull()?.sourceName) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            expandedSummaryChapterUrl = null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isGroupSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.sm)) {
            NovelGroupHeader(
                title = title,
                items = items,
                isExpanded = isExpanded,
                isSelectionMode = uiState.isSelectionMode,
                isGroupSelected = isGroupSelected,
                hasGroupSelection = hasGroupSelection,
                readerUiState = readerUiState,
                onToggleExpand = onToggleExpand,
                onToggleSelection = { libraryViewModel.toggleGroupSelection(title) },
                onOpenItem = { item ->
                    val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                    readerViewModel.loadContent(loadUrl, item.id)
                    libraryViewModel.markAsCurrentlyReading(item.id)
                    onCloseLibrary()
                },
                onOpenNewChapter = { item ->
                    libraryViewModel.openNewChapter(title, item.baseNovelUrl, item.sourceName) { url, id ->
                        readerViewModel.openChapterFromStart(url, id)
                        libraryViewModel.markAsCurrentlyReading(id)
                        onCloseLibrary()
                    }
                }
            )

            if (isExpanded) {
                NovelChapterList(
                    items = items,
                    uiState = uiState,
                    summaryUiState = summaryUiState,
                    showFullChapters = showFullChapters,
                    onToggleShowFull = onToggleShowFull,
                    expandedSummaryChapterUrl = expandedSummaryChapterUrl,
                    onToggleSummary = { chapterUrl ->
                        expandedSummaryChapterUrl =
                            if (expandedSummaryChapterUrl == chapterUrl) null else chapterUrl
                    },
                    onChapterClick = { chapter ->
                        if (uiState.isSelectionMode) {
                            libraryViewModel.toggleSelection(chapter.id)
                        } else {
                            val loadUrl = if (chapter.currentChapterUrl.isNotBlank()) chapter.currentChapterUrl else chapter.url
                            readerViewModel.loadContent(loadUrl, chapter.id)
                            libraryViewModel.markAsCurrentlyReading(chapter.id)
                            onCloseLibrary()
                        }
                    },
                    onChapterLongClick = { libraryViewModel.toggleSelection(it.id) },
                    summaryViewModel = summaryViewModel,
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableClickBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) {
                Modifier.combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick
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
private fun NovelGroupHeader(
    title: String,
    items: List<LibraryItem>,
    isExpanded: Boolean,
    isSelectionMode: Boolean,
    isGroupSelected: Boolean,
    hasGroupSelection: Boolean,
    readerUiState: ReaderViewModel.ReaderUiState,
    onToggleExpand: () -> Unit,
    onToggleSelection: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    onOpenNewChapter: (LibraryItem) -> Unit
): Unit {
    val resumeItem = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead } ?: items.first()
    val updateItem = latestLibraryUpdateItem(items)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isGroupSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        SelectableClickBox(
            modifier = Modifier.weight(1f),
            onClick = {
                if (isSelectionMode) onToggleSelection() else onOpenItem(resumeItem)
            },
            onLongClick = onToggleSelection
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!isExpanded) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                    Text(
                        text = "Resume ${resumeItem.currentChapter.ifBlank { "Chapter 1" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasGroupSelection && !isGroupSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (resumeItem.isCurrentlyReading) {
                        Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                        LinearProgressIndicator(
                            progress = { readerUiState.scrollProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }

        if (!isSelectionMode && updateItem != null) {
            AssistChip(
                onClick = { onOpenNewChapter(updateItem) },
                label = { Text("Open latest") }
            )
        }

        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Hide chapters" else "Browse chapters"
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelChapterList(
    items: List<LibraryItem>,
    uiState: LibraryViewModel.LibraryUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    showFullChapters: Boolean,
    onToggleShowFull: () -> Unit,
    expandedSummaryChapterUrl: String?,
    onToggleSummary: (String) -> Unit,
    onChapterClick: (LibraryItem) -> Unit,
    onChapterLongClick: (LibraryItem) -> Unit,
    summaryViewModel: SummaryViewModel,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel
): Unit {
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

    val lastRead = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead }
    if (!uiState.isSelectionMode && lastRead != null && lastRead.progress > 0) {
        Button(
            onClick = { onChapterClick(lastRead) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Resume ${lastRead.currentChapter.ifBlank { "reading" }}")
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
    }

    val visibleChapters = if (showFullChapters) items else items.take(3)

    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
        visibleChapters.forEach { chapterItem ->
            val isSelected = uiState.selectedIds.contains(chapterItem.id)
            val isCurrent = chapterItem.id == lastRead?.id
            val chapterUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url
            val isSummaryExpanded = expandedSummaryChapterUrl == chapterUrl
            val targetRowColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
                isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f)
                else -> Color.Transparent
            }
            val rowColor by animateColorAsState(
                targetValue = targetRowColor,
                animationSpec = tween(EasyReaderMotion.short),
                label = "chapterRowColor"
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                SelectableClickBox(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onChapterClick(chapterItem) },
                    onLongClick = { onChapterLongClick(chapterItem) }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = rowColor,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = EasyReaderSpacing.xs, vertical = EasyReaderSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                        ) {
                            if (uiState.isSelectionMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onChapterClick(chapterItem) }
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chapterItem.currentChapter.ifBlank { "Chapter 1" },
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else if (isCurrent) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (isCurrent) {
                                    Text(
                                        text = "Resume here",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }

                            if (!uiState.isSelectionMode) {
                                TextButton(onClick = { onToggleSummary(chapterUrl) }) {
                                    Text(if (isSummaryExpanded) "Hide summary" else "Chapter summary")
                                }
                            }
                        }
                    }
                }

                val cachedSummary = chapterItem.chapterSummaries[chapterUrl]
                val streamingSummary = if (summaryUiState.activeChapterUrl == chapterUrl) summaryUiState.currentSummary else cachedSummary

                AnimatedVisibility(
                    visible = isSummaryExpanded,
                    enter = expandVertically(animationSpec = tween(EasyReaderMotion.medium)) +
                        fadeIn(animationSpec = tween(EasyReaderMotion.short)),
                    exit = shrinkVertically(animationSpec = tween(EasyReaderMotion.short)) +
                        fadeOut(animationSpec = tween(EasyReaderMotion.short))
                ) {
                    ChapterSummaryDropdown(
                        summary = streamingSummary,
                        isGenerating = summaryUiState.isGenerating && summaryUiState.activeChapterUrl == chapterUrl,
                        isAvailable = summaryViewModel.isServiceReady() || summaryUiState.isInitializing,
                        onGenerateSummary = {
                            scope.launch {
                                val result = readerViewModel.contentRepository.loadContent(chapterUrl)
                                if (result is ContentResult.Success) {
                                    summaryViewModel.generateSummary(
                                        chapterUrl = chapterUrl,
                                        chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                                        content = result.elements
                                            .filterIsInstance<ContentElement.Text>()
                                            .map { it.content }
                                    ) { summary ->
                                        val updatedSummaries = chapterItem.chapterSummaries.toMutableMap()
                                        updatedSummaries[chapterUrl] = summary
                                        libraryViewModel.updateItem(
                                            chapterItem.copy(chapterSummaries = updatedSummaries)
                                        )
                                    }
                                }
                            }
                        },
                        onCancel = { summaryViewModel.cancelGeneration() },
                        modifier = Modifier.padding(top = EasyReaderSpacing.xxs)
                    )
                }
            }
        }

        if (items.size > 3) {
            TextButton(
                onClick = onToggleShowFull,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showFullChapters) "Show fewer chapters" else "Browse all chapters (${items.size})",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
