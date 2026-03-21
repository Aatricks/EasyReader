package io.aatricks.novelscraper.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.EpubBook
import io.aatricks.novelscraper.data.model.EpubTocItem
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.ui.components.ChapterSummaryDropdown
import io.aatricks.novelscraper.ui.theme.EasyReaderMotion
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.SummaryViewModel
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
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
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
                    item(key = "${sourceName}_$groupTitle") {
                        val firstItem = chapterItems.firstOrNull()
                        if (firstItem?.contentType == ContentType.EPUB) {
                            EpubItemCard(
                                item = firstItem,
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
                                isExpanded = expandedNovelState.getOrPut(groupTitle) { false },
                                showFullChapters = showFullChaptersState[groupTitle] ?: false,
                                onToggleExpand = {
                                    expandedNovelState[groupTitle] = !(expandedNovelState[groupTitle] ?: false)
                                },
                                onToggleShowFull = {
                                    showFullChaptersState[groupTitle] = !(showFullChaptersState[groupTitle] ?: false)
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = EasyReaderSpacing.xs),
        colors = CardDefaults.cardColors(
            containerColor = if (isGroupSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.sm)) {
            NovelGroupHeader(
                title = title,
                items = items,
                isExpanded = isExpanded,
                isSelectionMode = uiState.isSelectionMode,
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
                        readerViewModel.loadContent(url, id)
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
private fun SwipeSelectionBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onSwipeSelect: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 72.dp.toPx() } }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = (dragOffset * 0.18f).coerceAtLeast(0f)
            }
            .pointerInput(swipeThresholdPx, onSwipeSelect) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffset >= swipeThresholdPx) {
                            onSwipeSelect()
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (dragAmount > 0f) {
                            dragOffset = (dragOffset + dragAmount).coerceAtMost(swipeThresholdPx * 1.5f)
                        }
                    }
                )
            }
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick)
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
    readerUiState: ReaderViewModel.ReaderUiState,
    onToggleExpand: () -> Unit,
    onToggleSelection: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    onOpenNewChapter: (LibraryItem) -> Unit
): Unit {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwipeSelectionBox(
            modifier = Modifier
                .weight(1f)
                .padding(end = EasyReaderSpacing.xs),
            onClick = {
                if (isSelectionMode) onToggleSelection()
                else onOpenItem(items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead } ?: items.first())
            },
            onSwipeSelect = onToggleSelection
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasUpdates = items.any { it.hasUpdates }
                    val lastItem = items.lastOrNull()
                    val isCaughtUp = lastItem?.let { it.isCurrentlyReading || it.progress > 0 } ?: false

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hasUpdates && isCaughtUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (hasUpdates) {
                        Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                        Badge(modifier = Modifier.clickable { lastItem?.let(onOpenNewChapter) }) {
                            Text("NEW", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (!isExpanded) {
                    val current = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead } ?: items.first()
                    Text(
                        text = current.currentChapter.ifBlank { "Chapter 1" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (current.isCurrentlyReading) {
                        Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                        LinearProgressIndicator(
                            progress = { readerUiState.scrollProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
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
    onChapterClick: (LibraryItem) -> Unit,
    onChapterLongClick: (LibraryItem) -> Unit,
    summaryViewModel: SummaryViewModel,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel
): Unit {
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

    val lastRead = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead }
    if (lastRead != null && lastRead.progress > 0) {
        androidx.compose.material3.Button(
            onClick = { onChapterClick(lastRead) },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue: ${lastRead.currentChapter.ifBlank { "Reading" }}")
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
    }

    val visibleChapters = if (showFullChapters) items else items.take(3)

    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
        visibleChapters.forEach { chapterItem ->
            val isSelected = uiState.selectedIds.contains(chapterItem.id)
            val isCurrent = chapterItem.id == lastRead?.id
            val chapterUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url
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
                SwipeSelectionBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            rowColor,
                            shape = MaterialTheme.shapes.small
                        ),
                    onClick = { onChapterClick(chapterItem) },
                    onSwipeSelect = { onChapterLongClick(chapterItem) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = EasyReaderSpacing.xs, horizontal = EasyReaderSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chapterItem.currentChapter.ifBlank { "Chapter 1" },
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else if (isCurrent) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                val cachedSummary = chapterItem.chapterSummaries[chapterUrl]
                val streamingSummary = if (summaryUiState.activeChapterUrl == chapterUrl) summaryUiState.currentSummary else cachedSummary

                ChapterSummaryDropdown(
                    chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                    chapterUrl = chapterUrl,
                    summary = streamingSummary,
                    isGenerating = summaryUiState.isGenerating && summaryUiState.activeChapterUrl == chapterUrl,
                    onGenerateSummary = {
                        scope.launch {
                            val result = readerViewModel.contentRepository.loadContent(chapterUrl)
                            if (result is ContentResult.Success) {
                                summaryViewModel.generateSummary(
                                    chapterUrl = chapterUrl,
                                    chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                                    content = result.elements.filterIsInstance<ContentElement.Text>().map { it.content }
                                ) { summary ->
                                    val updatedSummaries = chapterItem.chapterSummaries.toMutableMap()
                                    updatedSummaries[chapterUrl] = summary
                                    libraryViewModel.updateItem(chapterItem.copy(chapterSummaries = updatedSummaries))
                                }
                            }
                        }
                    },
                    onCancel = { summaryViewModel.cancelGeneration() }
                )
            }
        }

        if (items.size > 3) {
            TextButton(
                onClick = onToggleShowFull,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showFullChapters) "Show Less" else "Show All (${items.size})",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

