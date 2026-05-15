package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.util.normalizeChapterList

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChapterListSheet(
    uiState: ReaderViewModel.ReaderUiState,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onNavigateToChapter: (String, String) -> Unit,
    sheetState: SheetState
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    var isDeleteMode by remember { mutableStateOf(false) }
    val selectedChapterUrls = remember { mutableStateListOf<String>() }
    val chaptersListState = rememberLazyListState()
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    var pendingBulkDelete by remember { mutableStateOf<Set<String>?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val libraryItemsInGroup = libraryUiState.groupedItems[uiState.baseTitle] ?: emptyList()
        val libraryUrls = libraryItemsInGroup.map { it.url }.toSet()
        val readUrls = libraryItemsInGroup.filter { it.progress == 100 }.map { it.url }.toSet()

        val allChapters = normalizeChapterList(
            uiState.fullChapterList.ifEmpty {
                libraryItemsInGroup.map {
                    ChapterInfo(it.currentChapter.ifBlank { it.title }, it.url)
                }
            }
        )
        val cacheStates = libraryUiState.chapterCacheStates

        val filteredChapters = if (isSelectionMode) {
            if (isDeleteMode) {
                allChapters.filter { it.url in libraryUrls }
            } else {
                allChapters.filter { it.url !in libraryUrls }
            }
        } else {
            allChapters
        }

        LaunchedEffect(allChapters) {
            libraryViewModel.refreshChapterCacheStates(allChapters.map { it.url })
        }

        LaunchedEffect(filteredChapters, uiState.content?.url) {
            val currentIndex = filteredChapters.indexOfFirst { it.url == uiState.content?.url }
            if (currentIndex >= 0) {
                chaptersListState.scrollToItem(currentIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
        ) {
            if (isSelectionMode) {
                Text(
                    text = "${selectedChapterUrls.size} selected",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            isSelectionMode = true
                            isDeleteMode = false
                            selectedChapterUrls.clear()
                            selectedChapterUrls.addAll(
                                computeUnreadChapterSelection(
                                    allChapters = allChapters,
                                    currentChapterUrl = uiState.content?.url,
                                    readUrls = readUrls,
                                    downloadedUrls = libraryUrls
                                )
                            )
                        },
                        label = { Text("Unread") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    AssistChip(
                        onClick = {
                            isSelectionMode = true
                            isDeleteMode = true
                            selectedChapterUrls.clear()
                            selectedChapterUrls.addAll(libraryUrls)
                        },
                        label = { Text("In library") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LibraryAddCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (isDeleteMode) {
                                val idsToRemove = selectedChapterUrls.mapNotNull { url ->
                                    libraryItemsInGroup.find { it.url == url }?.id
                                }.toSet()
                                if (idsToRemove.isNotEmpty()) {
                                    pendingBulkDelete = idsToRemove
                                }
                            } else {
                                val chaptersToDownload = selectedChapterUrls.mapNotNull { url ->
                                    allChapters.find { it.url == url }
                                }
                                if (chaptersToDownload.isNotEmpty()) {
                                    libraryViewModel.addChapters(
                                        chapters = chaptersToDownload,
                                        baseTitle = uiState.baseTitle,
                                        baseNovelUrl = uiState.baseNovelUrl,
                                        sourceName = uiState.sourceName
                                    )
                                }
                                isSelectionMode = false
                                selectedChapterUrls.clear()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isDeleteMode) Icons.Default.Delete else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(EasyReaderSpacing.xxs))
                        Text(
                            text = if (isDeleteMode) "Delete" else "Download",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            isSelectionMode = false
                            selectedChapterUrls.clear()
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(EasyReaderSpacing.xxs))
                        Text("Cancel")
                    }
                }
            }

            if (uiState.isChaptersLoading && uiState.fullChapterList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    state = chaptersListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                ) {
                    itemsIndexed(filteredChapters, key = { _, chapter -> chapter.url }) { index, chapter ->
                        val cacheState = cacheStates[chapter.url]
                        val libraryItem = libraryItemsInGroup.firstOrNull { it.url == chapter.url }
                        val isDownloaded = libraryItem?.isDownloaded == true
                        val isOfflineReady = isDownloaded || cacheState?.isComplete == true
                        val isCaching = cacheState?.isInProgress == true
                        val isInLibrary = chapter.url in libraryUrls
                        val isSelected = chapter.url in selectedChapterUrls
                        val isCurrent = chapter.url == uiState.content?.url
                        val statusText = chapterCacheStatusText(
                            isCurrent = isCurrent,
                            cacheState = cacheState,
                            isInLibrary = isInLibrary,
                            isDownloaded = isDownloaded
                        )

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = chapter.title,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isCurrent -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = if (statusText != null) {
                                {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else null,
                            trailingContent = {
                                if (!isSelectionMode && !isCurrent && !isCaching) {
                                    if (isDownloaded && libraryItem != null) {
                                        IconButton(
                                            onClick = { libraryViewModel.removeDownload(libraryItem.id) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DownloadDone,
                                                contentDescription = "Remove download",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else if (!isOfflineReady) {
                                        IconButton(
                                            onClick = { libraryViewModel.retryDownload(chapter.url) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Download",
                                                tint = if (statusText?.contains("partially") == true) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                },
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedChapterUrls.add(chapter.url)
                                            } else {
                                                selectedChapterUrls.remove(chapter.url)
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                } else if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Currently reading",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else if (isOfflineReady) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Saved offline",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (chapter.url in selectedChapterUrls) {
                                                selectedChapterUrls.remove(chapter.url)
                                            } else {
                                                selectedChapterUrls.add(chapter.url)
                                            }
                                        } else {
                                            onNavigateToChapter(chapter.url, chapter.title)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            isDeleteMode = isInLibrary
                                            selectedChapterUrls.clear()
                                            selectedChapterUrls.add(chapter.url)
                                        }
                                    }
                                ),
                            colors = ListItemDefaults.colors(
                                containerColor = when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
                                    isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.16f)
                                    else -> Color.Transparent
                                }
                            )
                        )

                        if (index < filteredChapters.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
        }
    }

    pendingBulkDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete ${ids.size} chapter${if (ids.size == 1) "" else "s"}?") },
            text = {
                Text("Removes the selected chapters from your library. Reading progress is preserved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    libraryViewModel.removeItems(ids)
                    pendingBulkDelete = null
                    isSelectionMode = false
                    selectedChapterUrls.clear()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun chapterCacheStatusText(
    isCurrent: Boolean,
    cacheState: PrefetchResult?,
    isInLibrary: Boolean,
    isDownloaded: Boolean = false
): String? {
    if (isCurrent) return "Currently reading"
    if (isDownloaded) return "Downloaded"
    if (cacheState?.isInProgress == true) return "Caching..."
    if (cacheState?.isComplete == true) return "Saved locally"
    if (cacheState != null && cacheState.totalImages > 0 && cacheState.cachedImages in 1 until cacheState.totalImages) {
        return "Saved partially: ${cacheState.cachedImages}/${cacheState.totalImages} images"
    }
    if (isInLibrary) return "In library"
    return null
}

internal fun computeUnreadChapterSelection(
    allChapters: List<ChapterInfo>,
    currentChapterUrl: String?,
    readUrls: Set<String>,
    downloadedUrls: Set<String>
): List<String> {
    val currentIndex = currentChapterUrl?.let { url ->
        allChapters.indexOfFirst { it.url == url }
    } ?: -1

    if (currentIndex < 0) return emptyList()

    return allChapters
        .asSequence()
        .filterIndexed { index, _ -> index > currentIndex }
        .map { it.url }
        .filter { it !in readUrls && it !in downloadedUrls }
        .toList()
}
