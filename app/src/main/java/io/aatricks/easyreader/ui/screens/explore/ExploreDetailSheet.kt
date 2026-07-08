package io.aatricks.easyreader.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExploreItemDetailSheet(
    item: ExploreItem,
    isLoading: Boolean = false,
    isInLibrary: Boolean = false,
    onAddToLibrary: () -> Unit,
    onRead: () -> Unit
): Unit {
    val imageRequest = rememberExploreImageRequest(item)
    var summaryExpanded by remember(item.url) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.lg, vertical = EasyReaderSpacing.sm)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .width(112.dp)
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isInLibrary) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "In your library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                MetaPill(text = item.source)
                item.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = "by $author",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.chapterCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${item.chapterCount} chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!item.rating.isNullOrBlank() || !item.rank.isNullOrBlank()) {
                    Text(
                        text = listOfNotNull(
                            item.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            item.rank?.takeIf { it.isNotBlank() }?.let { "#$it" }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            Button(
                onClick = onRead,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                Text(if (isInLibrary) "Read now" else "Add and read")
            }

            if (!isInLibrary) {
                OutlinedButton(
                    onClick = onAddToLibrary,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text("Save")
                }
            }
        }

        if (item.genres.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                Text(
                    text = "Genres",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                ) {
                    item.genres.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.sm))
                    Text(
                        text = "Loading details…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val rawSummary = item.summary?.takeIf { it.isNotBlank() }
                if (rawSummary == null) {
                    Text(
                        text = "Summary not available for this title yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val collapsedLineCount = 6
                    val needsToggle = rawSummary.length > 320 || rawSummary.lines().size > collapsedLineCount
                    Text(
                        text = rawSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35,
                        maxLines = if (summaryExpanded || !needsToggle) Int.MAX_VALUE else collapsedLineCount,
                        overflow = if (summaryExpanded || !needsToggle)
                            androidx.compose.ui.text.style.TextOverflow.Visible
                        else
                            androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (needsToggle) {
                        androidx.compose.material3.TextButton(
                            onClick = { summaryExpanded = !summaryExpanded },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Text(if (summaryExpanded) "Show less" else "Show more")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
    }
}
