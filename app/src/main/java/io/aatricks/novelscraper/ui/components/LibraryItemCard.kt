package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.LibraryItem

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
    onLongClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> Color(0xFF2C2C2C)
        isCurrent -> Color(0xFF1A1A1A)
        else -> Color(0xFF0D0D0D)
    }
    
    val borderColor = when {
        isSelected -> Color(0xFF4CAF50)
        isCurrent -> Color(0xFF4CAF50).copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected || isCurrent) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) Color(0xFF4CAF50) else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                // Current reading badge
                if (isCurrent) {
                    Badge(
                        containerColor = Color(0xFF4CAF50),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "READING",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black
                        )
                    }
                }
                
                // Selection indicator
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Chapter Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chapter ${item.currentChapter} / ${item.totalChapters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                
                Text(
                    text = "${item.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (item.progress == 100) Color(0xFF4CAF50) else Color.Gray
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Progress Bar
            LinearProgressIndicator(
                progress = item.progress / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    item.progress == 100 -> Color(0xFF4CAF50)
                    item.progress > 50 -> Color(0xFF2196F3)
                    else -> Color(0xFFFF9800)
                },
                trackColor = Color(0xFF2C2C2C)
            )
            
            // Download status indicator (if applicable)
            if (item.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF2196F3)
                    )
                    Text(
                        text = "Downloading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2196F3)
                    )
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            onLongClick = {}
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
            onLongClick = {}
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
            onLongClick = {}
        )
    }
}
