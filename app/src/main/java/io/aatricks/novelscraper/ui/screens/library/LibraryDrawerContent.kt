package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.ui.ExploreRoute
import io.aatricks.novelscraper.ui.LibraryRoute
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel

@Composable
fun LibraryDrawerContent(
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    onCloseDrawer: () -> Unit
): Unit {
    val libraryUiState by libraryViewModel.uiState.collectAsState()

    val continueItem = libraryUiState.currentlyReading
        ?: libraryUiState.items.maxByOrNull { it.lastRead }

    val recentUpdates = remember(libraryUiState.items, continueItem?.id) {
        libraryUiState.items
            .filter { it.hasUpdates }
            .groupBy { it.baseTitle.ifBlank { it.title } }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.dateAdded } }
            .filterNot { it.id == continueItem?.id }
            .sortedByDescending { it.dateAdded }
            .take(4)
    }

    val recentUpdateIds = remember(recentUpdates) {
        recentUpdates.map { it.id }.toSet()
    }

    val recentItems = remember(libraryUiState.items, continueItem?.id, recentUpdateIds) {
        libraryUiState.items
            .sortedByDescending { maxOf(it.lastRead, it.dateAdded) }
            .distinctBy { it.baseTitle.ifBlank { it.title } }
            .filterNot { it.id == continueItem?.id }
            .filterNot { it.id in recentUpdateIds }
            .take(6)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = EasyReaderSpacing.lg, vertical = EasyReaderSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                FilledTonalButton(
                    onClick = {
                        onCloseDrawer()
                        navController.navigate(LibraryRoute) {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Library")
                }
                OutlinedButton(
                    onClick = {
                        onCloseDrawer()
                        navController.navigate(ExploreRoute) {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Discover")
                }
            }
        }

        item {
            TextButton(
                onClick = {
                    onCloseDrawer()
                    onOpenFilePicker()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                Text("Import file")
            }
        }

        if (continueItem != null) {
            item {
                ContinueReadingCard(
                    item = continueItem,
                    onClick = {
                        openLibraryItem(
                            item = continueItem,
                            libraryViewModel = libraryViewModel,
                            readerViewModel = readerViewModel,
                            onCloseDrawer = onCloseDrawer
                        )
                    }
                )
            }
        }

        if (recentUpdates.isNotEmpty()) {
            item { DrawerSectionLabel("Latest updates") }
            items(recentUpdates, key = { "update_${it.id}" }) { item ->
                QuickLibraryItem(
                    item = item,
                    supportingText = "Start at the newest chapter",
                    trailingLabel = "Open latest",
                    onClick = {
                        openLatestUpdateItem(
                            item = item,
                            libraryViewModel = libraryViewModel,
                            readerViewModel = readerViewModel,
                            onCloseDrawer = onCloseDrawer
                        )
                    }
                )
            }
        }

        if (recentItems.isNotEmpty()) {
            item { DrawerSectionLabel("Recent") }
            items(recentItems, key = { "recent_${it.id}" }) { item ->
                QuickLibraryItem(
                    item = item,
                    supportingText = item.currentChapter.ifBlank { "Resume where you left off" }
                        .let { chapter ->
                            if (chapter.startsWith("Resume")) chapter else "Resume $chapter"
                        },
                    onClick = {
                        openLibraryItem(
                            item = item,
                            libraryViewModel = libraryViewModel,
                            readerViewModel = readerViewModel,
                            onCloseDrawer = onCloseDrawer
                        )
                    }
                )
            }
        }

        if (libraryUiState.items.isEmpty()) {
            item {
                EmptyQuickAccessState()
            }
        }
    }
}

private fun openLibraryItem(
    item: LibraryItem,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    onCloseDrawer: () -> Unit
): Unit {
    val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
    readerViewModel.loadContent(loadUrl, item.id)
    libraryViewModel.markAsCurrentlyReading(item.id)
    onCloseDrawer()
}

private fun openLatestUpdateItem(
    item: LibraryItem,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    onCloseDrawer: () -> Unit
): Unit {
    val baseTitle = item.baseTitle.ifBlank { item.title }
    if (item.baseNovelUrl.isBlank() || item.sourceName.isBlank()) {
        val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
        readerViewModel.openChapterFromStart(loadUrl, item.id)
        libraryViewModel.markAsCurrentlyReading(item.id)
        onCloseDrawer()
        return
    }

    libraryViewModel.openNewChapter(baseTitle, item.baseNovelUrl, item.sourceName) { url, id ->
        readerViewModel.openChapterFromStart(url, id)
        libraryViewModel.markAsCurrentlyReading(id)
        onCloseDrawer()
    }
}

@Composable
private fun ContinueReadingCard(
    item: LibraryItem,
    onClick: () -> Unit
): Unit {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EasyReaderSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            Text(
                text = "Continue Reading",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = item.baseTitle.ifBlank { item.title },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.currentChapter.ifBlank { "Pick up where you left off" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String): Unit {
    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickLibraryItem(
    item: LibraryItem,
    supportingText: String,
    trailingLabel: String? = null,
    onClick: () -> Unit
): Unit {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
            ) {
                Text(
                    text = item.baseTitle.ifBlank { item.title },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailingLabel != null) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(trailingLabel) }
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyQuickAccessState(): Unit {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(EasyReaderSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowOutward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Start your library",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "Use Discover to find something new or import a file directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
