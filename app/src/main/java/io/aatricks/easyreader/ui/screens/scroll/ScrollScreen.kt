package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.MilestoneState
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.repository.FinishedSeriesData
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ScrollViewModel

// Strip geometry
private const val SCROLL_HEIGHT_DP = 340f
internal const val SEGMENT_WIDTH_DP = 220f
private const val ENDCAP_WIDTH_DP = 232f
internal const val ROLLER_WIDTH_DP = 13f
internal const val MOUNTING_BAND_DP = 9f
private const val EDGE_MARGIN_DP = 20f

// Vignettes (finished series)
private const val VIGNETTE_FRAME_WIDTH_DP = 66f
private const val VIGNETTE_FRAME_HEIGHT_DP = 92f
private const val VIGNETTE_FRAME_PADDING_DP = 3f
private const val VIGNETTE_LABEL_WIDTH_DP = 96f
private const val VIGNETTE_Y_HIGH_DP = 30f
private const val VIGNETTE_Y_LOW_DP = 74f
private const val VIGNETTE_ROTATION_DEG = 2.5f
private const val VIGNETTE_CORNER_DP = 3f
private const val VIGNETTE_ELEVATION_DP = 3f
private const val VIGNETTE_LABEL_ALPHA = 0.85f
private const val VIGNETTE_LABEL_PADDING_H_DP = 6f
private const val VIGNETTE_LABEL_PADDING_V_DP = 2f

// Hanko seals (milestones)
private const val STAMP_SIZE_DP = 30f
private const val STAMP_Y_DP = 252f
private const val STAMP_ROTATION_DEG = 4f
private const val STAMP_CORNER_DP = 5f
private const val STAMP_BORDER_DP = 1.6f
private const val STAMP_ALPHA = 0.78f
private const val STAMP_FILL_ALPHA = 0.10f
private const val STAMP_LABEL_ALPHA = 0.65f
private const val SEAL_COLOR = 0xFFB3392E

// Rank end-cap
private const val PROGRESS_BAR_WIDTH_DP = 128f
private const val PROGRESS_TRACK_ALPHA = 0.12f

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

private val MILESTONE_KANJI = mapOf(
    "first_chapter" to "始",
    "first_series" to "巻",
    "chapters_100" to "百",
    "chapters_1000" to "千",
    "hours_10" to "墨",
    "hours_100" to "刻",
    "series_10" to "十",
    "days_30" to "日",
    "night_reader" to "夜",
    "marathon" to "長",
    "epic_series" to "大",
)
private const val DEFAULT_SEAL_KANJI = "証"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScrollViewModel = hiltViewModel()
) {
    val progression by viewModel.progression.collectAsState()
    val finishedSeries by viewModel.finishedSeries.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.markMilestonesSeen()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Scroll") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val scrollState = rememberScrollState()

            LaunchedEffect(scrollState.maxValue) {
                if (scrollState.maxValue > 0) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SCROLL_HEIGHT_DP.dp)
                    .horizontalScroll(scrollState)
            ) {
                ScrollPainting(progression = progression, finishedSeries = finishedSeries)
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.xl))

            StatsFooter(progression)
        }
    }
}

@Composable
private fun ScrollPainting(
    progression: ScrollProgression,
    finishedSeries: List<FinishedSeriesData>
) {
    val paintingWidth = ((progression.level + 1) * SEGMENT_WIDTH_DP).dp
    val totalWidth = paintingWidth + ENDCAP_WIDTH_DP.dp

    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val paper = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, PAPER_BLEND)

    Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawScrollPainting(ink = ink, paper = paper, paintingPx = paintingWidth.toPx())
        }

        finishedSeries.forEachIndexed { index, series ->
            val fraction = (index + 1).toFloat() / (finishedSeries.size + 1)
            val x = (paintingWidth * fraction - (VIGNETTE_LABEL_WIDTH_DP / 2).dp)
                .coerceIn(EDGE_MARGIN_DP.dp, paintingWidth - VIGNETTE_LABEL_WIDTH_DP.dp)
            val y = if (index % 2 == 0) VIGNETTE_Y_HIGH_DP.dp else VIGNETTE_Y_LOW_DP.dp
            SeriesVignette(
                series = series,
                index = index,
                modifier = Modifier.offset(x = x, y = y)
            )
        }

        val unlocked = progression.milestones.filter { it.unlockedAtMs != null }
        unlocked.forEachIndexed { index, milestone ->
            val fraction = (index + 1).toFloat() / (unlocked.size + 1)
            val x = (paintingWidth * fraction).coerceIn(
                EDGE_MARGIN_DP.dp,
                paintingWidth - EDGE_MARGIN_DP.dp
            )
            HankoSeal(
                milestone = milestone,
                index = index,
                modifier = Modifier.offset(x = x, y = STAMP_Y_DP.dp)
            )
        }

        RankEndCap(
            progression = progression,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(ENDCAP_WIDTH_DP.dp)
                .fillMaxHeight()
        )
    }
}

// -- Overlays --------------------------------------------------------------

@Composable
private fun SeriesVignette(series: FinishedSeriesData, index: Int, modifier: Modifier = Modifier) {
    val rotation = if (index % 2 == 0) -VIGNETTE_ROTATION_DEG else VIGNETTE_ROTATION_DEG
    Column(
        modifier = modifier
            .width(VIGNETTE_LABEL_WIDTH_DP.dp)
            .rotate(rotation),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(VIGNETTE_CORNER_DP.dp),
            shadowElevation = VIGNETTE_ELEVATION_DP.dp,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                HAIRLINE_DP.dp,
                MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.size(VIGNETTE_FRAME_WIDTH_DP.dp, VIGNETTE_FRAME_HEIGHT_DP.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(VIGNETTE_FRAME_PADDING_DP.dp)) {
                if (series.coverImageUrl.isNotBlank()) {
                    AsyncImage(
                        model = series.coverImageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(VIGNETTE_CORNER_DP.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = VIGNETTE_LABEL_ALPHA),
            shape = RoundedCornerShape(VIGNETTE_CORNER_DP.dp)
        ) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = VIGNETTE_LABEL_PADDING_H_DP.dp,
                    vertical = VIGNETTE_LABEL_PADDING_V_DP.dp
                )
            )
        }
    }
}

@Composable
private fun HankoSeal(milestone: MilestoneState, index: Int, modifier: Modifier = Modifier) {
    val sealColor = Color(SEAL_COLOR)
    val rotation = if (index % 2 == 0) -STAMP_ROTATION_DEG else STAMP_ROTATION_DEG
    Column(
        modifier = modifier.rotate(rotation),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(STAMP_SIZE_DP.dp)
                .background(
                    sealColor.copy(alpha = STAMP_FILL_ALPHA),
                    RoundedCornerShape(STAMP_CORNER_DP.dp)
                )
                .border(
                    STAMP_BORDER_DP.dp,
                    sealColor.copy(alpha = STAMP_ALPHA),
                    RoundedCornerShape(STAMP_CORNER_DP.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = MILESTONE_KANJI[milestone.id] ?: DEFAULT_SEAL_KANJI,
                style = MaterialTheme.typography.labelMedium,
                color = sealColor.copy(alpha = STAMP_ALPHA)
            )
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
        Text(
            text = milestone.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = STAMP_LABEL_ALPHA)
        )
    }
}

@Composable
private fun RankEndCap(progression: ScrollProgression, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = progression.rankName,
                style = MaterialTheme.typography.headlineMedium,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.md))
            LinearProgressIndicator(
                progress = {
                    val current = progression.xpIntoLevel.toFloat()
                    val next = progression.xpToNextLevel.toFloat()
                    if (next == 0f) 1f else current / (current + next)
                },
                modifier = Modifier.width(PROGRESS_BAR_WIDTH_DP.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = PROGRESS_TRACK_ALPHA)
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
            Text(
                text = "Level ${progression.level}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatsFooter(progression: ScrollProgression) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatItem(
            label = "Reading time",
            value = formatReadingTime(progression.totalActiveMillis),
            modifier = Modifier.weight(1f)
        )
        StatItem(
            label = "Chapters",
            value = progression.totalChaptersCompleted.toString(),
            modifier = Modifier.weight(1f)
        )
        StatItem(
            label = "Series",
            value = progression.finishedSeriesCount.toString(),
            modifier = Modifier.weight(1f)
        )
        StatItem(
            label = "Days",
            value = progression.readingDayCount.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatReadingTime(millis: Long): String {
    val totalMinutes = millis / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return "${hours}h ${minutes}m"
}
