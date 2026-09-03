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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.ui.components.ErrorTile
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ExploreViewModel

/**
 * Reads the open item straight off [uiState] (the same shape [ExploreGrid] takes) so the About
 * section can tell a failed detail fetch apart from a title that genuinely has no summary.
 */
@Composable
fun ExploreItemDetailSheet(
    uiState: ExploreViewModel.ExploreUiState,
    isInLibrary: Boolean,
    onRetryDetails: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRead: () -> Unit
): Unit {
    val item = uiState.selectedItemDetails ?: uiState.selectedItem ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.lg, vertical = EasyReaderSpacing.sm)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
    ) {
        DetailHeader(item = item, isInLibrary = isInLibrary)
        DetailActions(isInLibrary = isInLibrary, onRead = onRead, onAddToLibrary = onAddToLibrary)
        GenreSection(genres = item.genres)
        HorizontalDivider()
        AboutSection(item = item, uiState = uiState, onRetryDetails = onRetryDetails)
        Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
    }
}

@Composable
private fun DetailHeader(item: ExploreItem, isInLibrary: Boolean) {
    val imageRequest = rememberExploreImageRequest(item)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
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
                IconLabel(
                    icon = Icons.Default.CheckCircle,
                    text = "In your library",
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 14.dp,
                    bold = true
                )
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
                IconLabel(
                    icon = Icons.Default.AutoStories,
                    text = "${item.chapterCount} chapters",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 14.dp,
                    bold = false
                )
            }
            RatingLine(item)
        }
    }
}

@Composable
private fun IconLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    iconSize: androidx.compose.ui.unit.Dp,
    bold: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Text(
            text = text,
            style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RatingLine(item: ExploreItem) {
    if (item.rating.isNullOrBlank() && item.rank.isNullOrBlank()) return
    Text(
        text = listOfNotNull(
            item.rating?.takeIf { it.isNotBlank() }?.let { "\u2605 $it" },
            item.rank?.takeIf { it.isNotBlank() }?.let { "#$it" }
        ).joinToString("  \u00b7  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DetailActions(isInLibrary: Boolean, onRead: () -> Unit, onAddToLibrary: () -> Unit) {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreSection(genres: List<String>) {
    if (genres.isEmpty()) return
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
            genres.forEach { tag ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = tag,
                        modifier = Modifier.padding(
                            horizontal = EasyReaderSpacing.sm,
                            vertical = EasyReaderSpacing.xs
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    item: ExploreItem,
    uiState: ExploreViewModel.ExploreUiState,
    onRetryDetails: () -> Unit
) {
    var summaryExpanded by remember(item.url) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        val rawSummary = item.summary?.takeIf { it.isNotBlank() }
        when {
            uiState.isFetchingDetails -> LoadingDetailsRow()
            uiState.detailsFailed && rawSummary == null -> ErrorTile(
                message = "Could not load this title's details.",
                onRetry = onRetryDetails
            )
            rawSummary == null -> Text(
                text = "Summary not available for this title yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> SummaryText(
                summary = rawSummary,
                expanded = summaryExpanded,
                onToggle = { summaryExpanded = !summaryExpanded }
            )
        }
    }
}

@Composable
private fun LoadingDetailsRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(EasyReaderSpacing.sm))
        Text(
            text = "Loading details\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryText(summary: String, expanded: Boolean, onToggle: () -> Unit) {
    val collapsedLineCount = 6
    val needsToggle = summary.length > 320 || summary.lines().size > collapsedLineCount
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35,
        maxLines = if (expanded || !needsToggle) Int.MAX_VALUE else collapsedLineCount,
        overflow = if (expanded || !needsToggle) TextOverflow.Visible else TextOverflow.Ellipsis
    )
    if (needsToggle) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "Show less" else "Show more")
        }
    }
}
