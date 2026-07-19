package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.MilestoneState
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.repository.FinishedSeriesData
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ScrollViewModel

// Strip geometry (SEGMENT/MOUNTING/ROLLER/HAIRLINE shared with the painting renderer)
internal const val SEGMENT_WIDTH_DP = 260f
internal const val MOUNTING_BAND_DP = 8f
internal const val ROLLER_WIDTH_DP = 13f
internal const val HAIRLINE_DP = 1f
private const val ENDCAP_WIDTH_DP = 288f
private const val EDGE_MARGIN_DP = 20f

// Overlay placement, as fractions of the strip height
private const val VIGNETTE_Y_HIGH_FRACTION = 0.055f
private const val VIGNETTE_Y_LOW_FRACTION = 0.17f
private const val STAMP_Y_FRACTION = 0.6f

// Vignettes (finished series)
private const val VIGNETTE_FRAME_WIDTH_DP = 76f
private const val VIGNETTE_FRAME_HEIGHT_DP = 104f
private const val VIGNETTE_FRAME_PADDING_DP = 3f
private const val VIGNETTE_LABEL_WIDTH_DP = 108f
private const val VIGNETTE_ROTATION_DEG = 2.5f
private const val VIGNETTE_CORNER_DP = 4f
private const val VIGNETTE_ELEVATION_DP = 8f
private const val VIGNETTE_LABEL_ALPHA = 0.85f
private const val VIGNETTE_LABEL_PADDING_H_DP = 7f
private const val VIGNETTE_LABEL_PADDING_V_DP = 2f

// Hanko seals (milestones): solid vermilion, kanji knocked out in paper
private const val STAMP_SIZE_DP = 36f
private const val STAMP_ROTATION_DEG = 4f
private const val STAMP_CORNER_DP = 7f
private const val STAMP_KANJI_SP = 17
private const val STAMP_LABEL_SPACING_SP = 1.2f
private const val STAMP_LABEL_SP = 9

// Rank end-cap medallion
private const val MEDALLION_SIZE_DP = 112f
private const val MEDALLION_STROKE_DP = 7f
private const val MEDALLION_TRACK_ALPHA = 0.16f
private const val MEDALLION_OUTER_RING_GAP_DP = 5f
private const val MEDALLION_OUTER_RING_ALPHA = 0.35f
private const val MEDALLION_START_ANGLE = -90f
private const val FULL_SWEEP = 360f
private const val RANK_TEXT_SP = 28
private const val LEVEL_CAPTION_ALPHA = 0.7f
private const val LEVEL_LABEL_SPACING_SP = 2.5f

// Stats card
private const val STATS_CARD_ALPHA = 0.72f
private const val STATS_BORDER_ALPHA = 0.35f
private const val STATS_LABEL_SP = 10
private const val STATS_VALUE_SP = 19
private const val TIME_STAT_WEIGHT = 1.45f
private const val STATS_LABEL_SPACING_SP = 1.1f

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
    val palette = rememberScrollPalette()

    LaunchedEffect(Unit) {
        viewModel.markMilestonesSeen()
    }

    Scaffold(
        containerColor = palette.skyTop,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Your Scroll",
                        fontFamily = FontFamily.Serif,
                        color = palette.labelInk
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = palette.labelInk
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.skyTop)
            )
        }
    ) { padding ->
        ScrollCanvasArea(
            progression = progression,
            finishedSeries = finishedSeries,
            palette = palette,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
private fun ScrollCanvasArea(
    progression: ScrollProgression,
    finishedSeries: List<FinishedSeriesData>,
    palette: ScrollPalette,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(modifier = modifier) {
            val stripHeight = maxHeight
            val paintingWidth = ((progression.level + 1) * SEGMENT_WIDTH_DP).dp
            val totalWidth = paintingWidth + ENDCAP_WIDTH_DP.dp

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawScrollPainting(
                    palette = palette,
                    scrollPx = scrollState.value.toFloat(),
                    totalPx = totalWidth.toPx()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
            ) {
                ScrollOverlays(
                    progression = progression,
                    finishedSeries = finishedSeries,
                    palette = palette,
                    paintingWidth = paintingWidth,
                    stripHeight = stripHeight
                )
            }

            StatsCard(
                progression = progression,
                palette = palette,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = EasyReaderSpacing.lg,
                        end = EasyReaderSpacing.lg,
                        bottom = EasyReaderSpacing.xl
                    )
            )
    }
}

@Composable
private fun ScrollOverlays(
    progression: ScrollProgression,
    finishedSeries: List<FinishedSeriesData>,
    palette: ScrollPalette,
    paintingWidth: Dp,
    stripHeight: Dp
) {
    Box(modifier = Modifier.width(paintingWidth + ENDCAP_WIDTH_DP.dp).fillMaxHeight()) {
        finishedSeries.forEachIndexed { index, series ->
            val fraction = (index + 1).toFloat() / (finishedSeries.size + 1)
            val x = (paintingWidth * fraction - (VIGNETTE_LABEL_WIDTH_DP / 2).dp)
                .coerceIn(EDGE_MARGIN_DP.dp, paintingWidth - VIGNETTE_LABEL_WIDTH_DP.dp)
            val yFraction = if (index % 2 == 0) VIGNETTE_Y_HIGH_FRACTION else VIGNETTE_Y_LOW_FRACTION
            SeriesVignette(
                series = series,
                index = index,
                palette = palette,
                modifier = Modifier.offset(x = x, y = stripHeight * yFraction)
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
                palette = palette,
                modifier = Modifier.offset(x = x, y = stripHeight * STAMP_Y_FRACTION)
            )
        }

        RankEndCap(
            progression = progression,
            palette = palette,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(ENDCAP_WIDTH_DP.dp)
                .fillMaxHeight()
                .padding(end = ROLLER_WIDTH_DP.dp, bottom = (MOUNTING_BAND_DP * 2).dp)
        )
    }
}

@Composable
private fun SeriesVignette(
    series: FinishedSeriesData,
    index: Int,
    palette: ScrollPalette,
    modifier: Modifier = Modifier
) {
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
            color = palette.frame,
            border = BorderStroke(HAIRLINE_DP.dp, palette.gold),
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
            color = palette.frame.copy(alpha = VIGNETTE_LABEL_ALPHA),
            shape = RoundedCornerShape(VIGNETTE_CORNER_DP.dp)
        ) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.labelSmall,
                color = palette.labelInk,
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
private fun HankoSeal(
    milestone: MilestoneState,
    index: Int,
    palette: ScrollPalette,
    modifier: Modifier = Modifier
) {
    val rotation = if (index % 2 == 0) -STAMP_ROTATION_DEG else STAMP_ROTATION_DEG
    Column(
        modifier = modifier.rotate(rotation),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(STAMP_SIZE_DP.dp)
                .background(palette.vermilion, RoundedCornerShape(STAMP_CORNER_DP.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = MILESTONE_KANJI[milestone.id] ?: DEFAULT_SEAL_KANJI,
                fontSize = STAMP_KANJI_SP.sp,
                fontWeight = FontWeight.Bold,
                color = palette.sealKanji
            )
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
        Text(
            text = milestone.name.uppercase(),
            fontSize = STAMP_LABEL_SP.sp,
            letterSpacing = STAMP_LABEL_SPACING_SP.sp,
            fontWeight = FontWeight.Medium,
            color = palette.labelInk
        )
    }
}

@Composable
private fun RankEndCap(
    progression: ScrollProgression,
    palette: ScrollPalette,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(ENDCAP_SCRIM_SIZE_DP.dp)
                .background(
                    Brush.radialGradient(
                        0f to palette.frame.copy(alpha = ENDCAP_SCRIM_ALPHA),
                        1f to palette.frame.copy(alpha = 0f)
                    )
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LevelMedallion(progression, palette)
            Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
            Text(
                text = progression.rankName,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = RANK_TEXT_SP.sp),
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = palette.labelInk
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
            Text(
                text = "${progression.xpToNextLevel} XP to level ${progression.level + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = palette.labelInk.copy(alpha = LEVEL_CAPTION_ALPHA)
            )
        }
    }
}

private const val ENDCAP_SCRIM_SIZE_DP = 340f
private const val ENDCAP_SCRIM_ALPHA = 0.55f

@Composable
private fun LevelMedallion(progression: ScrollProgression, palette: ScrollPalette) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(MEDALLION_SIZE_DP.dp)) {
            val strokePx = MEDALLION_STROKE_DP.dp.toPx()
            val ringGap = MEDALLION_OUTER_RING_GAP_DP.dp.toPx()
            val arcSize = Size(size.width - strokePx - ringGap * 2, size.height - strokePx - ringGap * 2)
            val arcOffset = Offset(strokePx / 2 + ringGap, strokePx / 2 + ringGap)
            // Faint full outer ring, a mounting for the progress arc
            drawCircle(
                color = palette.gold.copy(alpha = MEDALLION_OUTER_RING_ALPHA),
                radius = size.width / 2,
                style = Stroke(width = HAIRLINE_DP.dp.toPx())
            )
            drawArc(
                color = palette.labelInk.copy(alpha = MEDALLION_TRACK_ALPHA),
                startAngle = 0f,
                sweepAngle = FULL_SWEEP,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            val current = progression.xpIntoLevel.toFloat()
            val next = progression.xpToNextLevel.toFloat()
            val fraction = if (current + next <= 0f) 1f else current / (current + next)
            drawArc(
                color = palette.gold,
                startAngle = MEDALLION_START_ANGLE,
                sweepAngle = FULL_SWEEP * fraction,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LEVEL",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = LEVEL_LABEL_SPACING_SP.sp,
                color = palette.labelInk.copy(alpha = LEVEL_CAPTION_ALPHA)
            )
            Text(
                text = progression.level.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = palette.gold
            )
        }
    }
}

@Composable
private fun StatsCard(
    progression: ScrollProgression,
    palette: ScrollPalette,
    modifier: Modifier = Modifier
) {
    Surface(
        color = palette.frame.copy(alpha = STATS_CARD_ALPHA),
        shape = RoundedCornerShape(EasyReaderSpacing.lg),
        border = BorderStroke(HAIRLINE_DP.dp, palette.gold.copy(alpha = STATS_BORDER_ALPHA)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = EasyReaderSpacing.md,
                vertical = EasyReaderSpacing.md
            ),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem(
                "TIME",
                formatReadingTime(progression.totalActiveMillis),
                palette,
                Modifier.weight(TIME_STAT_WEIGHT)
            )
            StatItem("CHAPTERS", progression.totalChaptersCompleted.toString(), palette, Modifier.weight(1f))
            StatItem("SERIES", progression.finishedSeriesCount.toString(), palette, Modifier.weight(1f))
            StatItem("DAYS", progression.readingDayCount.toString(), palette, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, palette: ScrollPalette, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = STATS_VALUE_SP.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            color = palette.gold
        )
        Text(
            text = label,
            fontSize = STATS_LABEL_SP.sp,
            letterSpacing = STATS_LABEL_SPACING_SP.sp,
            color = palette.labelInk.copy(alpha = LEVEL_CAPTION_ALPHA)
        )
    }
}

internal fun formatReadingTime(millis: Long): String {
    val totalMinutes = millis / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return "${hours}h ${minutes}m"
}
