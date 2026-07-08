package io.aatricks.easyreader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.hasActionableUpdate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun rememberLibraryCoverImageRequest(item: LibraryItem): ImageRequest {
    val context = LocalContext.current

    return remember(item.coverImageUrl, item.url, item.sourceName) {
        val refererUrl = item.baseNovelUrl.ifBlank { item.url }
        val uri = try {
            java.net.URI(refererUrl)
        } catch (_: Exception) {
            null
        }

        var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else refererUrl
        if (item.sourceName == "MangaBat" || referer.contains("mangabat")) {
            referer = "https://www.mangabats.com/"
        } else if (referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(item.coverImageUrl)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
            .crossfade(true)
            .build()
    }
}

private val COVER_THUMBNAIL_WIDTH = 56.dp
private const val COVER_ASPECT_RATIO = 0.6666667f

/**
 * Library item card component displaying novel information and progress.
 * 
 * Features:
 * - Novel title with text overflow handling
 * - Progress bar showing reading completion (0-100%)
 * - Current reading indicator with accent color
 * - Selection state with visual feedback
 * - Click and long-click handlers
 * - Chapter progress display
 * - Dark theme styling
 * 
 * @param item The library item to display
 * @param isSelected Whether the item is currently selected
 * @param isCurrent Whether this is the currently reading novel
 * @param onClick Callback for single click
 * @param onLongClick Callback for long press
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryItemCard(
    item: LibraryItem,
    isSelected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onNewTagClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onResetProgress: (() -> Unit)? = null,
    onMarkFinished: (() -> Unit)? = null
) {
    val hapticFeedback = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val targetBackgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surface
    }
    
    val targetBorderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    val targetScale = if (isSelected) 0.992f else 1f

    val backgroundColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 300),
        label = "backgroundColor"
    )
    
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(durationMillis = 300),
        label = "borderColor"
    )

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 220),
        label = "scale"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isSelected || isCurrent) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .semantics {
                selected = isSelected
            },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.coverImageUrl.isNotBlank()) {
                val imageRequest = rememberLibraryCoverImageRequest(item)
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .width(COVER_THUMBNAIL_WIDTH)
                        .aspectRatio(COVER_ASPECT_RATIO)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                // Title Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Current reading badge
                    if (isCurrent) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = EasyReaderSpacing.xs)
                        ) {
                            Text(
                                text = "Reading",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else if (item.hasActionableUpdate()) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = EasyReaderSpacing.xs)
                                .then(
                                    if (onNewTagClick != null) {
                                        Modifier.clickable { onNewTagClick() }
                                    } else Modifier
                                )
                        ) {
                            Text(
                                text = "New",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiary
                            )
                        }
                    }
                    
                    // Selection indicator
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = EasyReaderSpacing.xs)
                                .size(22.dp)
                        )
                    } else if (onDelete != null || onResetProgress != null || onMarkFinished != null) {
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "More actions",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (onMarkFinished != null && item.progress < 100) {
                                    DropdownMenuItem(
                                        text = { Text("Mark as finished") },
                                        onClick = {
                                            menuExpanded = false
                                            onMarkFinished()
                                        }
                                    )
                                }
                                if (onResetProgress != null && item.progress > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Reset progress") },
                                        onClick = {
                                            menuExpanded = false
                                            onResetProgress()
                                        }
                                    )
                                }
                                if (onDelete != null) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from library") },
                                        onClick = {
                                            menuExpanded = false
                                            onDelete()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Chapter Progress
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chapterNumber = io.aatricks.easyreader.util.TextUtils.extractChapterNumber(item.currentChapter)
                    val isLastChapter = chapterNumber != null && item.totalChapters > 0 && chapterNumber.toInt() >= item.totalChapters
                    
                    val chapterText = if (isLastChapter) {
                        val numberStr = if (chapterNumber % 1.0 == 0.0) {
                            chapterNumber.toInt().toString()
                        } else {
                            chapterNumber.toString()
                        }
                        "Last Chapter - $numberStr"
                    } else {
                        "Chapter ${item.currentChapter} / ${item.totalChapters}"
                    }
    
                    Text(
                        text = chapterText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = "${item.progress}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (item.progress == 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Progress Bar
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .semantics { contentDescription = "${item.progress} percent read" },
                    color = when {
                        item.progress == 100 -> MaterialTheme.colorScheme.primary
                        item.progress > 50 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                // Download status indicator (if applicable)
                if (item.isDownloading) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Downloading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Preview composable for LibraryItemCard
 */
@Composable
fun LibraryItemCardPreview() {
    Column(
        modifier = Modifier
            .background(Color.Black)
            .padding(EasyReaderSpacing.md),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
    ) {
        LibraryItemCard(
            item = LibraryItem(
                id = "1",
                title = "The Great Novel: A Very Long Title That Should Wrap",
                url = "https://example.com/novel/chapter-42",
                currentChapter = "42",
                totalChapters = 100,
                progress = 42,
                isDownloading = false
            ),
            isSelected = false,
            isCurrent = true,
            onClick = {},
            onLongClick = {},
            onNewTagClick = {}
        )
        
        LibraryItemCard(
            item = LibraryItem(
                id = "2",
                title = "Another Story",
                url = "https://example.com/story/chapter-10",
                currentChapter = "10",
                totalChapters = 50,
                progress = 20,
                isDownloading = true
            ),
            isSelected = true,
            isCurrent = false,
            onClick = {},
            onLongClick = {},
            onNewTagClick = {}
        )
        
        LibraryItemCard(
            item = LibraryItem(
                id = "3",
                title = "Completed Novel",
                url = "https://example.com/completed/chapter-200",
                currentChapter = "200",
                totalChapters = 200,
                progress = 100,
                isDownloading = false
            ),
            isSelected = false,
            isCurrent = false,
            onClick = {},
            onLongClick = {},
            onNewTagClick = {}
        )
    }
}
