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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.LibraryItem
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.aatricks.easyreader.ui.components.ChapterSummaryDropdown
import io.aatricks.easyreader.ui.components.rememberLibraryCoverImageRequest
import io.aatricks.easyreader.ui.screens.library.LibraryFlattenState
import io.aatricks.easyreader.ui.screens.library.LibraryRenderItem
import io.aatricks.easyreader.ui.screens.library.flattenLibraryItems
import io.aatricks.easyreader.ui.screens.library.novelChapterRow
import io.aatricks.easyreader.ui.screens.library.selectableClickBox
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
    val expandedSummaryState = remember { mutableStateMapOf<String, String?>() }
    val renderItems = flattenLibraryItems(
        LibraryFlattenState(
            groupedBySource = uiState.groupedBySource,
            collapsedSources = uiState.collapsedSources,
            expandedNovels = expandedNovelState,
            showFullChapters = showFullChaptersState,
            expandedSummaryChapterUrls = expandedSummaryState,
            isSelectionMode = uiState.isSelectionMode
        )
    )
    val context = LibraryRenderContext(
        uiState = uiState,
        readerUiState = readerUiState,
        summaryUiState = summaryUiState,
        libraryViewModel = libraryViewModel,
        readerViewModel = readerViewModel,
        summaryViewModel = summaryViewModel,
        expandedNovels = expandedNovelState,
        showFullChapters = showFullChaptersState,
        expandedSummaries = expandedSummaryState,
        onCloseLibrary = onCloseLibrary
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = EasyReaderSpacing.xs, bottom = EasyReaderSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
    ) {
        items(
            items = renderItems,
            key = LibraryRenderItem::key,
            contentType = { it::class }
        ) { renderItem ->
            libraryRenderItem(renderItem, context)
        }
    }
}

internal data class LibraryRenderContext(
    val uiState: LibraryViewModel.LibraryUiState,
    val readerUiState: ReaderViewModel.ReaderUiState,
    val summaryUiState: SummaryViewModel.SummaryUiState,
    val libraryViewModel: LibraryViewModel,
    val readerViewModel: ReaderViewModel,
    val summaryViewModel: SummaryViewModel,
    val expandedNovels: MutableMap<String, Boolean>,
    val showFullChapters: MutableMap<String, Boolean>,
    val expandedSummaries: MutableMap<String, String?>,
    val onCloseLibrary: () -> Unit
)

@Composable
private fun libraryRenderItem(item: LibraryRenderItem, context: LibraryRenderContext) = with(context) {
    when (item) {
        is LibraryRenderItem.SourceHeader -> SourceHeader(
            name = item.sourceName,
            isExpanded = item.isExpanded,
            onClick = { libraryViewModel.toggleSourceExpansion(item.sourceName) }
        )
        is LibraryRenderItem.EpubRow -> EpubItemCard(
            item = item.item,
            uiState = uiState,
            contentRepository = readerViewModel.contentRepository,
            readerViewModel = readerViewModel,
            libraryViewModel = libraryViewModel,
            onCloseLibrary = onCloseLibrary
        )
        is LibraryRenderItem.NovelHeader -> novelGroupCard(item, context)
        is LibraryRenderItem.NovelResumeButton -> novelResumeButton(item.item) {
            openLibraryChapter(item.item, context)
        }
        is LibraryRenderItem.ChapterRow -> novelChapterRow(item, context)
        is LibraryRenderItem.ShowMoreControl -> showMoreControl(item, context)
    }
}

@Composable
private fun showMoreControl(item: LibraryRenderItem.ShowMoreControl, context: LibraryRenderContext) {
    TextButton(
        onClick = { context.showFullChapters[item.groupKey] = !item.showFullChapters },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (item.showFullChapters) {
                "Show fewer chapters"
            } else {
                "Browse all chapters (${item.chapterCount})"
            },
            color = MaterialTheme.colorScheme.primary
        )
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

@Composable
private fun novelResumeButton(item: LibraryItem, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            if (item.progress == 0 && item.currentChapterUrl.isBlank()) {
                "Start reading"
            } else {
                "Resume ${item.currentChapter.ifBlank { "reading" }}"
            }
        )
    }
}

internal fun openLibraryChapter(
    item: LibraryItem,
    context: LibraryRenderContext
): Unit = with(context) {
    if (uiState.isSelectionMode) {
        libraryViewModel.toggleSelection(item.id)
        return
    }
    val loadUrl = item.currentChapterUrl.ifBlank { item.url }
    readerViewModel.loadContent(loadUrl, item.id)
    libraryViewModel.markAsCurrentlyReading(item.id)
    onCloseLibrary()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun novelGroupCard(
    header: LibraryRenderItem.NovelHeader,
    context: LibraryRenderContext
): Unit = with(context) {
    val title = header.title
    val items = header.items
    val isExpanded = header.isExpanded
    val isGroupSelected = items.all { it.id in uiState.selectedIds }
    val hasGroupSelection = items.any { it.id in uiState.selectedIds }

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
                onToggleExpand = {
                    expandedNovels[header.groupKey] = !header.isExpanded
                    if (header.isExpanded) expandedSummaries[header.groupKey] = null
                },
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
                },
                onResetProgress = { libraryViewModel.resetNovelProgress(title) },
                onRemoveGroup = { libraryViewModel.removeGroup(title) }
            )
        }
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
    onOpenNewChapter: (LibraryItem) -> Unit,
    onResetProgress: () -> Unit,
    onRemoveGroup: () -> Unit
): Unit {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasProgress = items.any { it.progress > 0 }
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

        selectableClickBox(
            modifier = Modifier.weight(1f),
            onClick = {
                if (isSelectionMode) onToggleSelection() else onOpenItem(resumeItem)
            },
            onLongClick = onToggleSelection
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val coverItem = items.firstOrNull { it.coverImageUrl.isNotBlank() }
                if (coverItem != null) {
                    AsyncImage(
                        model = rememberLibraryCoverImageRequest(coverItem),
                        contentDescription = null,
                        modifier = Modifier
                            .width(48.dp)
                            .aspectRatio(GROUP_COVER_ASPECT_RATIO)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.sm))
                }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isExpanded) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                    Text(
                        text = getLibraryItemResumeLabel(resumeItem),
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
                if (!isSelectionMode && updateItem != null) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                    AssistChip(
                        onClick = { onOpenNewChapter(updateItem) },
                        label = { Text("Open latest") }
                    )
                }
            }
            }
        }

        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Hide chapters" else "Browse chapters"
            )
        }

        if (!isSelectionMode) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions"
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (hasProgress) {
                        DropdownMenuItem(
                            text = { Text("Reset reading progress") },
                            onClick = {
                                menuExpanded = false
                                onResetProgress()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        onClick = {
                            menuExpanded = false
                            onRemoveGroup()
                        }
                    )
                }
            }
        }
    }
}

private const val GROUP_COVER_ASPECT_RATIO = 2f / 3f
