package io.aatricks.easyreader.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import io.aatricks.easyreader.ui.components.rememberLibraryCoverImageRequest
import androidx.hilt.navigation.compose.hiltViewModel
import io.aatricks.easyreader.ui.viewmodel.ScrollViewModel
import io.aatricks.easyreader.ui.ScrollRoute

private val DRAWER_HERO_COVER_WIDTH = 56.dp
private val DRAWER_COVER_WIDTH = 40.dp
private const val COVER_ASPECT_RATIO = 0.6666667f

@Composable
fun LibraryDrawerContent(
    drawerSections: DrawerNovelSections,
    isLibraryEmpty: Boolean,
    onOpenFilePicker: () -> Unit,
    onCloseDrawer: () -> Unit,
    onLibraryClick: () -> Unit,
    onDiscoverClick: () -> Unit,
    onScrollClick: () -> Unit,
    onOpenLibraryItem: (LibraryItem) -> Unit,
    onOpenLatestUpdate: (LibraryItem) -> Unit
): Unit {
    val scrollViewModel: ScrollViewModel = hiltViewModel()
    val continueNovel = drawerSections.continueNovel
    val recentUpdates = drawerSections.recentUpdates
    val recentItems = drawerSections.recentNovels

    val scrollProgression by scrollViewModel.progression.collectAsState()
    val unseenMilestones by scrollViewModel.unseenMilestoneCount.collectAsState()
    val scrollEnabled by scrollViewModel.gamificationEnabled.collectAsState()

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
                        onLibraryClick()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Library")
                }
                OutlinedButton(
                    onClick = {
                        onCloseDrawer()
                        onDiscoverClick()
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

        if (scrollEnabled) {
            item {
                NavigationDrawerItem(
                    label = { Text(scrollProgression.rankName) },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    badge = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                progress = {
                                    val current = scrollProgression.xpIntoLevel.toFloat()
                                    val next = scrollProgression.xpToNextLevel.toFloat()
                                    if (next == 0f) 1f else current / (current + next)
                                },
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                            if (unseenMilestones > 0) {
                                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    },
                    selected = false,
                    onClick = {
                        onCloseDrawer()
                        onScrollClick()
                    }
                )
            }
        }

        if (continueNovel != null) {
            item {
                ContinueReadingCard(
                    item = continueNovel.resumeItem,
                    onClick = {
                        onOpenLibraryItem(continueNovel.resumeItem)
                        onCloseDrawer()
                    }
                )
            }
        }

        if (recentUpdates.isNotEmpty()) {
            item { DrawerSectionLabel("Latest updates") }
            items(recentUpdates, key = { "update_${it.novelKey}" }) { novel ->
                val chapter = novel.updateItem.currentChapter.ifBlank {
                    novel.updateItem.title.takeIf { it != novel.updateItem.baseTitle } ?: "New chapter"
                }.ifBlank { "New chapter" }
                QuickLibraryItem(
                    item = novel.updateItem,
                    supportingText = chapter,
                    onClick = {
                        onOpenLatestUpdate(novel.updateItem)
                        onCloseDrawer()
                    }
                )
            }
        }

        if (recentItems.isNotEmpty()) {
            item { DrawerSectionLabel("Recent") }
            items(recentItems, key = { "recent_${it.novelKey}" }) { novel ->
                QuickLibraryItem(
                    item = novel.resumeItem,
                    supportingText = if (novel.resumeItem.progress == 0 &&
                        novel.resumeItem.currentChapterUrl.isBlank()
                    ) {
                        "Start reading"
                    } else {
                        val chapter = novel.resumeItem.currentChapter.ifBlank { "Resume where you left off" }
                        if (chapter.startsWith("Resume")) chapter else "Resume $chapter"
                    },
                    onClick = {
                        onOpenLibraryItem(novel.resumeItem)
                        onCloseDrawer()
                    }
                )
            }
        }

        if (isLibraryEmpty) {
            item {
                EmptyQuickAccessState()
            }
        }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EasyReaderSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.coverImageUrl.isNotBlank()) {
                val imageRequest = rememberLibraryCoverImageRequest(item)
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .width(DRAWER_HERO_COVER_WIDTH)
                        .aspectRatio(COVER_ASPECT_RATIO)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.md))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
            ) {
                Text(
                    text = "Continue Reading",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.libraryDisplayTitle(),
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
private fun QuickLibraryItemCover(item: LibraryItem): Unit {
    if (item.coverImageUrl.isNotBlank()) {
        val imageRequest = rememberLibraryCoverImageRequest(item)
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .width(DRAWER_COVER_WIDTH)
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(EasyReaderSpacing.md))
    }
}

@Composable
private fun QuickLibraryItem(
    item: LibraryItem,
    supportingText: String,
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
            QuickLibraryItemCover(item)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
            ) {
                Text(
                    text = item.libraryDisplayTitle(),
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
