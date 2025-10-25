package io.aatricks.novelscraper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Expandable card component for displaying AI-generated chapter summaries
 * 
 * @param chapterTitle The title of the chapter
 * @param chapterUrl Unique identifier for the chapter
 * @param summary The AI-generated summary text (null if not generated yet)
 * @param isGenerating Whether summary is currently being generated
 * @param onGenerateSummary Callback when user requests summary generation
 */
@Composable
fun ChapterSummaryDropdown(
    chapterTitle: String,
    chapterUrl: String,
    summary: String?,
    isGenerating: Boolean,
    onGenerateSummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        if (summary == null && !isGenerating) {
                            onGenerateSummary()
                        }
                        isExpanded = !isExpanded
                    }
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "AI Summary",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Summary",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                }
                
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    when {
                        isGenerating -> {
                            // Loading state
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFF4CAF50),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Generating summary...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }
                        summary != null -> {
                            // Summary available
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = Color(0xFF0D0D0D),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFE0E0E0),
                                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                                )
                            }
                        }
                        else -> {
                            // Not generated yet - show button
                            Button(
                                onClick = onGenerateSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Psychology,
                                    contentDescription = "Generate",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Generate AI Summary",
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
