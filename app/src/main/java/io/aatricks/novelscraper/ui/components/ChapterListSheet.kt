package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch

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
    val selectedChapterUrls = remember { mutableStateListOf<String>() }
    var isDeleteMode by remember { mutableStateOf(false) }
    val chaptersListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val libraryItemsInGroup = libraryViewModel.uiState.value.groupedItems[uiState.baseTitle] ?: emptyList()
        val downloadedUrls = libraryItemsInGroup.map { it.url }.toSet()

        val allChapters = uiState.fullChapterList.ifEmpty {
            libraryItemsInGroup.map {
                ChapterInfo(it.currentChapter.ifBlank { it.title }, it.url)
            }
        }

        val filteredChapters = if (isSelectionMode) {
            if (isDeleteMode) {
                allChapters.filter { it.url in downloadedUrls }
            } else {
                allChapters.filter { it.url !in downloadedUrls }
            }
        } else {
            allChapters
        }

        LaunchedEffect(Unit) {
            val currentIndex = filteredChapters.indexOfFirst { it.url == uiState.content?.url }
            if (currentIndex >= 0) {
                chaptersListState.scrollToItem(currentIndex)
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSelectionMode) {
                        if (isDeleteMode) "Delete Chapters (${selectedChapterUrls.size})"
                        else "Download Chapters (${selectedChapterUrls.size})"
                    } else "Chapters",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val readUrls = libraryItemsInGroup.filter { it.progress == 100 }.map { it.url }.toSet()

                    IconButton(onClick = {
                        isSelectionMode = true
                        isDeleteMode = false
                        selectedChapterUrls.clear()
                        val unread = allChapters.filter { it.url !in readUrls && it.url !in downloadedUrls }
                        selectedChapterUrls.addAll(unread.map { it.url })
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                            contentDescription = "Select All Unread",
                            tint = if (isSelectionMode && !isDeleteMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = {
                        isSelectionMode = true
                        isDeleteMode = true
                        selectedChapterUrls.clear()
                        selectedChapterUrls.addAll(downloadedUrls)
                    }) {
                        Icon(
                            imageVector = Icons.Default.LibraryAddCheck,
                            contentDescription = "Select All Downloaded",
                            tint = if (isSelectionMode && isDeleteMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSelectionMode) {
                        IconButton(onClick = {
                            if (isDeleteMode) {
                                val idsToRemove = selectedChapterUrls.mapNotNull { url ->
                                    libraryItemsInGroup.find { it.url == url }?.id
                                }.toSet()
                                if (idsToRemove.isNotEmpty()) {
                                    libraryViewModel.removeItems(idsToRemove)
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
                            }
                            isSelectionMode = false
                            selectedChapterUrls.clear()
                        }) {
                            Icon(
                                imageVector = if (isDeleteMode) Icons.Default.Delete else Icons.Default.Download,
                                contentDescription = if (isDeleteMode) "Delete" else "Download",
                                tint = if (isDeleteMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedChapterUrls.clear()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.isChaptersLoading && uiState.fullChapterList.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    state = chaptersListState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)
                ) {
                    items(filteredChapters) { chapter ->
                        val isDownloaded = chapter.url in downloadedUrls
                        val isSelected = chapter.url in selectedChapterUrls

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = chapter.title,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        chapter.url == uiState.content?.url -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            trailingContent = {
                                if (!isSelectionMode && isDownloaded) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Downloaded",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else if (isSelectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedChapterUrls.add(chapter.url)
                                            else selectedChapterUrls.remove(chapter.url)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                }
                            },
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (chapter.url in selectedChapterUrls) selectedChapterUrls.remove(chapter.url)
                                            else selectedChapterUrls.add(chapter.url)
                                        } else {
                                            onNavigateToChapter(chapter.url, chapter.title)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            isDeleteMode = isDownloaded
                                            selectedChapterUrls.add(chapter.url)
                                        }
                                    }
                                ),
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                            )
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
