package io.aatricks.easyreader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.EpubBook
import io.aatricks.easyreader.data.model.EpubTocItem
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpubItemCard(
    item: LibraryItem,
    uiState: LibraryViewModel.LibraryUiState,
    contentRepository: ContentRepository,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    val hapticFeedback = LocalHapticFeedback.current
    var epubBook by remember { mutableStateOf<EpubBook?>(null) }
    var isExpanded by remember { mutableStateOf(false) }
    val isSelected = item.id in uiState.selectedIds

    LaunchedEffect(item.url) {
        epubBook = contentRepository.getEpubBook(item.url)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { libraryViewModel.toggleSelection(item.id) }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    libraryViewModel.toggleSelection(item.id)
                                } else {
                                    epubBook?.let { book ->
                                        val firstHref = book.getFirstReadableHref()
                                        if (firstHref != null) {
                                            readerViewModel.loadEpubChapter(item.url, firstHref, item.id)
                                            libraryViewModel.markAsCurrentlyReading(item.id)
                                            onCloseLibrary()
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                libraryViewModel.toggleSelection(item.id)
                            }
                        )
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    epubBook?.let { book ->
                        Text(
                            text = book.metadata.author ?: "Unknown Author",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (isExpanded) epubBook?.let { book ->
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)) {
                    book.toc.forEach { tocItem ->
                        EpubTocItemView(
                            tocItem = tocItem,
                            epubPath = item.url,
                            itemId = item.id,
                            readerViewModel = readerViewModel,
                            libraryViewModel = libraryViewModel,
                            onCloseLibrary = onCloseLibrary,
                            depth = 0
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubTocItemView(
    tocItem: EpubTocItem,
    epubPath: String,
    itemId: String,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onCloseLibrary: () -> Unit,
    depth: Int = 0
) {
    var isExpanded by remember { mutableStateOf(false) }
    val startPadding = when (depth) {
        0 -> 0.dp
        1 -> EasyReaderSpacing.md
        2 -> EasyReaderSpacing.xxl
        else -> 48.dp
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = {
                        readerViewModel.loadEpubChapter(epubPath, tocItem.href, itemId)
                        libraryViewModel.markAsCurrentlyReading(itemId)
                        onCloseLibrary()
                    }
                )
                .padding(start = startPadding, top = EasyReaderSpacing.xs, bottom = EasyReaderSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tocItem.hasChildren()) {
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xxs))
            } else {
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xl))
            }

            Text(
                text = tocItem.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (isExpanded && tocItem.hasChildren()) {
            Column {
                tocItem.children.forEach { child ->
                    EpubTocItemView(
                        tocItem = child,
                        epubPath = epubPath,
                        itemId = itemId,
                        readerViewModel = readerViewModel,
                        libraryViewModel = libraryViewModel,
                        onCloseLibrary = onCloseLibrary,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}
