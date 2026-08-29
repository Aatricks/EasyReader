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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.R
import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.isStrictOfflineReady
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.util.areChapterUrlsMatching
import io.aatricks.easyreader.util.matchChapterIndex
import io.aatricks.easyreader.util.normalizeChapterList
import io.aatricks.easyreader.util.normalizeChapterUrl

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChapterListSheet(
    uiState: ReaderViewModel.ReaderUiState,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onNavigateToChapter: (String, String) -> Unit,
    onDownloadRemoved: () -> Unit,
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
        val libraryItemsInGroup = libraryUiState.groupedItems[uiState.baseTitle]
            ?: libraryUiState.items.filter {
                it.libraryDisplayTitle().equals(uiState.baseTitle, ignoreCase = true) ||
                    it.baseTitle.equals(uiState.baseTitle, ignoreCase = true) ||
                    it.title.equals(uiState.baseTitle, ignoreCase = true)
            }
        val libraryItemsByUrl = libraryItemsInGroup.associateBy { it.url }
        val libraryItemsByNormalizedUrl = libraryItemsInGroup.associateBy { normalizeChapterUrl(it.url) }
        val libraryUrls = libraryItemsInGroup.map { it.url }.toSet()
        val downloadedUrls = libraryItemsInGroup
            .asSequence()
            .filter { it.isDownloaded }
            .map { it.url }
            .toSet()
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
                allChapters.filter { ch -> libraryUrls.any { areChapterUrlsMatching(it, ch.url) } }
            } else {
                allChapters.filter { ch -> downloadedUrls.none { areChapterUrlsMatching(it, ch.url) } }
            }
        } else {
            allChapters
        }

        val currentIndex = matchChapterIndex(filteredChapters, uiState.content?.url, uiState.chapterTitle)

        LaunchedEffect(allChapters) {
            libraryViewModel.refreshChapterCacheStates(allChapters.map { it.url })
        }

        LaunchedEffect(filteredChapters, uiState.content?.url) {
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
                    text = stringResource(R.string.chapter_selection_count, selectedChapterUrls.size),
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
                                    downloadedUrls = downloadedUrls
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.chapter_filter_unread)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                                contentDescription = stringResource(R.string.chapter_filter_unread),
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
                        label = { Text(stringResource(R.string.chapter_filter_in_library)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LibraryAddCheck,
                                contentDescription = stringResource(R.string.chapter_filter_in_library),
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
                            contentDescription = if (isDeleteMode) {
                                stringResource(R.string.common_delete)
                            } else {
                                stringResource(R.string.download_button)
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(EasyReaderSpacing.xxs))
                        Text(
                            text = if (isDeleteMode) stringResource(R.string.common_delete) else stringResource(R.string.download_button),
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
                        Text(stringResource(R.string.common_cancel))
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
                        val libraryItem = libraryItemsByUrl[chapter.url]
                            ?: libraryItemsByNormalizedUrl[normalizeChapterUrl(chapter.url)]
                            ?: libraryItemsInGroup.find { areChapterUrlsMatching(it.url, chapter.url) }
                        val isDownloaded = libraryItem?.isDownloaded == true ||
                            downloadedUrls.any { areChapterUrlsMatching(it, chapter.url) }
                        val isInLibrary = libraryItem != null ||
                            chapter.url in libraryUrls ||
                            libraryUrls.any { areChapterUrlsMatching(it, chapter.url) }
                        val isSelected = chapter.url in selectedChapterUrls
                        val isCurrent = areChapterUrlsMatching(chapter.url, uiState.content?.url) ||
                            (currentIndex >= 0 && index == currentIndex)
                        val statusKind = chapterCacheStatusKind(
                            isCurrent = isCurrent,
                            cacheState = cacheState,
                            isInLibrary = isInLibrary,
                            isDownloaded = isDownloaded
                        )
                        val isOfflineReady = statusKind == ChapterStatus.Downloaded
                        val isDownloading = statusKind == ChapterStatus.Caching
                        val statusText = chapterCacheStatusLabel(statusKind)

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
                                if (!isSelectionMode && !isCurrent && !isDownloading) {
                                    if (isOfflineReady && libraryItem != null) {
                                        IconButton(
                                            onClick = {
                                                libraryViewModel.removeDownload(libraryItem.id)
                                                onDownloadRemoved()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DownloadDone,
                                                contentDescription = stringResource(R.string.chapter_action_remove_download),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else if (!isOfflineReady) {
                                        IconButton(
                                            onClick = {
                                                if (libraryItem != null) {
                                                    libraryViewModel.retryDownload(chapter.url)
                                                } else {
                                                    libraryViewModel.addChapters(
                                                        chapters = listOf(chapter),
                                                        baseTitle = uiState.baseTitle,
                                                        baseNovelUrl = uiState.baseNovelUrl,
                                                        sourceName = uiState.sourceName
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = stringResource(R.string.download_button),
                                                tint = if (statusKind is ChapterStatus.DownloadIncomplete) {
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
                                        contentDescription = stringResource(R.string.chapter_status_currently_reading),
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else if (isOfflineReady) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.chapter_status_saved_offline),
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
            title = {
                Text(pluralStringResource(R.plurals.bulk_delete_title, ids.size, ids.size))
            },
            text = {
                Text(stringResource(R.string.bulk_delete_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    libraryViewModel.removeItemsImmediate(ids)
                    pendingBulkDelete = null
                    isSelectionMode = false
                    selectedChapterUrls.clear()
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * Status kind for a chapter row. Plain data so this function stays unit-testable;
 * callers translate to user strings via [ChapterStatus.label].
 */
internal sealed class ChapterStatus {
    data object CurrentlyReading : ChapterStatus()
    data object Downloaded : ChapterStatus()
    data object Caching : ChapterStatus()
    data object VerifyingDownload : ChapterStatus()
    data class DownloadIncomplete(val cached: Int, val total: Int) : ChapterStatus()
    data object InLibrary : ChapterStatus()
}

internal fun chapterCacheStatusKind(
    isCurrent: Boolean,
    cacheState: PrefetchResult?,
    isInLibrary: Boolean,
    isDownloaded: Boolean = false
): ChapterStatus? {
    if (isCurrent) return ChapterStatus.CurrentlyReading
    val hasManagedDownload = isDownloaded || (isInLibrary && cacheState?.isPersistentDownload == true)
    if (cacheState?.isInProgress == true && (isInLibrary || hasManagedDownload)) return ChapterStatus.Caching
    if (hasManagedDownload && cacheState != null) {
        // "Downloaded" requires the chapter to be fully present on disk. Chapters where some
        // images ended up as permanent failures (404/403/etc.) are reported as incomplete so
        // the user sees the missing-image count and a retry control instead of a misleading
        // "Downloaded" badge that would then surface "Image unavailable" on open.
        if (cacheState.isStrictOfflineReady()) {
            return ChapterStatus.Downloaded
        }
        if (cacheState.isPersistentDownload && cacheState.totalImages > 0) {
            return ChapterStatus.DownloadIncomplete(cacheState.cachedImages, cacheState.totalImages)
        }
    }
    // Device transfer / old installs can restore the DB flag without the corresponding
    // files. Until a downloads-tier inspect proves the files are present, do not show the
    // chapter as Downloaded.
    if (isDownloaded && cacheState == null) return ChapterStatus.VerifyingDownload
    if (isInLibrary) return ChapterStatus.InLibrary
    return null
}

/**
 * Back-compat helper used by existing tests. Returns the English label for the resolved
 * status kind. New UI code should call [chapterCacheStatusKind] and resolve via
 * [stringResource] so translations apply.
 */
internal fun chapterCacheStatusText(
    isCurrent: Boolean,
    cacheState: PrefetchResult?,
    isInLibrary: Boolean,
    isDownloaded: Boolean = false
): String? = when (val kind = chapterCacheStatusKind(isCurrent, cacheState, isInLibrary, isDownloaded)) {
    ChapterStatus.CurrentlyReading -> "Currently reading"
    ChapterStatus.Downloaded -> "Downloaded"
    ChapterStatus.Caching -> "Downloading..."
    ChapterStatus.VerifyingDownload -> "Verifying download..."
    is ChapterStatus.DownloadIncomplete -> "Download incomplete: ${kind.cached}/${kind.total} images"
    ChapterStatus.InLibrary -> "In library"
    null -> null
}

@Composable
internal fun chapterCacheStatusLabel(status: ChapterStatus?): String? = when (status) {
    ChapterStatus.CurrentlyReading -> stringResource(R.string.chapter_status_currently_reading)
    ChapterStatus.Downloaded -> stringResource(R.string.chapter_status_downloaded)
    ChapterStatus.Caching -> stringResource(R.string.chapter_status_caching)
    ChapterStatus.VerifyingDownload -> stringResource(R.string.chapter_status_verifying_download)
    is ChapterStatus.DownloadIncomplete -> stringResource(
        R.string.chapter_status_download_incomplete,
        status.cached,
        status.total
    )
    ChapterStatus.InLibrary -> stringResource(R.string.chapter_status_in_library)
    null -> null
}

internal fun computeUnreadChapterSelection(
    allChapters: List<ChapterInfo>,
    currentChapterUrl: String?,
    readUrls: Set<String>,
    downloadedUrls: Set<String>
): List<String> {
    val currentIndex = currentChapterUrl?.let { url ->
        matchChapterIndex(allChapters, url)
    } ?: -1

    if (currentIndex < 0) return emptyList()

    return allChapters
        .asSequence()
        .filterIndexed { index, _ -> index > currentIndex }
        .map { it.url }
        .filter { url ->
            readUrls.none { areChapterUrlsMatching(it, url) } &&
                downloadedUrls.none { areChapterUrlsMatching(it, url) }
        }
        .toList()
}
