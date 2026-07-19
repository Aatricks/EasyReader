package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.MilestoneState
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.repository.FinishedSeriesData
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ScrollViewModel
import kotlin.random.Random

private const val SCROLL_HEIGHT_DP = 360f
private const val SEGMENT_WIDTH_DP = 200f
private const val VIGNETTE_WIDTH_DP = 90f
private const val VIGNETTE_HEIGHT_DP = 90f
private const val VIGNETTE_IMAGE_WIDTH_DP = 60f
private const val VIGNETTE_Y_OFFSET_DP = 40f
private const val VIGNETTE_BASE_OFFSET_DP = 100f
private const val VIGNETTE_STRIDE_DP = 250f
private const val VIGNETTE_ROTATION_DEG = 3f
private const val STAMP_SIZE_DP = 34f
private const val STAMP_BASE_OFFSET_DP = 50f
private const val STAMP_STRIDE_DP = 180f
private const val STAMP_Y_OFFSET_DP = 260f
private const val PROGRESS_BAR_WIDTH_DP = 120f
private const val MOTIF_MOON_RADIUS_DP = 20f
private const val MOTIF_TORII_WIDTH_DP = 30f
private const val MOTIF_TORII_HEIGHT_DP = 25f
private const val MOTIF_TORII_STROKE_DP = 3f
private const val MOTIF_WAVE_SIZE_DP = 30f
private const val MOTIF_WAVE_HEIGHT_DP = 15f
private const val ALPHA_SURFACE = 0.5f
private const val ALPHA_MOTIF = 0.3f
private const val ALPHA_SEAL = 0.8f
private const val ALPHA_PROGRESS = 0.1f
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val LAYER_BASE_START_Y = 0.4f
private const val LAYER_STEP = 0.15f
private const val LAYER_NOISE_BASE = 0.1f
private const val LAYER_CP1X_RATIO = 0.33f
private const val LAYER_CP2X_RATIO = 0.66f
private const val LAYER_ALPHA_BASE = 0.1f
private const val LAYER_ALPHA_STEP = 0.05f
private const val LAYER_COUNT_MIN = 2
private const val LAYER_COUNT_MAX_EXCLUSIVE = 4
private const val MOTIF_SPACING = 3
private const val MOTIF_COUNT = 3
private const val MOTIF_X_OFFSET = 0.2f
private const val MOTIF_X_RANGE = 0.6f
private const val MOTIF_Y_OFFSET = 0.2f
private const val MOTIF_Y_RANGE = 0.2f
private const val SEAL_COLOR_VAL = 0xFFB3392E

private const val MOTIF_TORII_BEAM_STROKE_DP = 4f
private const val MOTIF_TORII_TOP_BEAM_OFFSET_DP = 5f
private const val MOTIF_TORII_BOTTOM_BEAM_OFFSET_DP = 12f
private const val MOTIF_TORII_BOTTOM_BEAM_STROKE_DP = 2f
private const val MOTIF_WAVE_START_ANGLE = 180f
private const val MOTIF_WAVE_SWEEP_ANGLE = 180f
private const val MOTIF_WAVE_OFFSET_X_BASE_DP = 15f
private const val MOTIF_WAVE_OFFSET_X_STRIDE_DP = 10f
private const val MOTIF_WAVE_OFFSET_Y_STRIDE_DP = 5f
private const val MOTIF_WAVE_STROKE_DP = 2f
private const val VIGNETTE_CORNER_RADIUS_DP = 4f
private const val VIGNETTE_ELEVATION_DP = 4f
private const val VIGNETTE_BORDER_WIDTH_DP = 1f
private const val STAMP_CORNER_RADIUS_DP = 8f
private const val STAMP_BORDER_WIDTH_DP = 2f
private const val STAMP_PADDING_DP = 4f
private const val VIGNETTE_LABEL_PADDING_H_DP = 4f
private const val VIGNETTE_LABEL_PADDING_V_DP = 2f

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

            ScrollProgressionRow(
                progression = progression,
                finishedSeries = finishedSeries,
                scrollState = scrollState
            )

            Spacer(modifier = Modifier.height(EasyReaderSpacing.xl))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EasyReaderSpacing.lg),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Total Reading", value = formatReadingTime(progression.totalActiveMillis))
                StatItem(label = "Chapters", value = progression.totalChaptersCompleted.toString())
                StatItem(label = "Series Finished", value = progression.finishedSeriesCount.toString())
                StatItem(label = "Reading Days", value = progression.readingDayCount.toString())
            }
        }
    }
}

@Composable
private fun ScrollProgressionRow(
    progression: ScrollProgression,
    finishedSeries: List<FinishedSeriesData>,
    scrollState: ScrollState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SCROLL_HEIGHT_DP.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_SURFACE))
            .horizontalScroll(scrollState)
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            for (i in 0..progression.level) {
                ScrollSegment(
                    level = i,
                    modifier = Modifier
                        .width(SEGMENT_WIDTH_DP.dp)
                        .fillMaxHeight()
                )
            }

            ProgressHeader(progression)
        }

        // Vignettes
        finishedSeries.forEachIndexed { index, series ->
            val xOffset = VIGNETTE_BASE_OFFSET_DP.dp + (index * VIGNETTE_STRIDE_DP).dp
            SeriesVignette(
                series = series,
                index = index,
                modifier = Modifier.offset(x = xOffset, y = VIGNETTE_Y_OFFSET_DP.dp)
            )
        }

        // Hanko stamps
        val visibleMilestones = progression.milestones.filter { it.unlockedAtMs != null }
        visibleMilestones.forEachIndexed { index, milestone ->
            val xOffset = STAMP_BASE_OFFSET_DP.dp + (index * STAMP_STRIDE_DP).dp
            HankoStamp(
                milestone = milestone,
                modifier = Modifier.offset(x = xOffset, y = STAMP_Y_OFFSET_DP.dp)
            )
        }
    }
}

@Composable
private fun ProgressHeader(progression: ScrollProgression) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = EasyReaderSpacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = progression.rankName,
                style = MaterialTheme.typography.headlineMedium,
                fontStyle = FontStyle.Italic,
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
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ALPHA_PROGRESS)
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
private fun ScrollSegment(level: Int, modifier: Modifier = Modifier) {
    val inkColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val random = Random(level)

        // Draw 2-3 layers of mountains
        val layers = random.nextInt(LAYER_COUNT_MIN, LAYER_COUNT_MAX_EXCLUSIVE)
        for (i in 0 until layers) {
            val path = Path()
            val yJitter = random.nextFloat() * LAYER_NOISE_BASE
            val startY = h * (LAYER_BASE_START_Y + (i * LAYER_STEP) + yJitter)
            path.moveTo(0f, startY)
            
            val cp1x = w * LAYER_CP1X_RATIO
            val cp1y = startY - h * (LAYER_NOISE_BASE + random.nextFloat() * (LAYER_NOISE_BASE * 2f))
            val cp2x = w * LAYER_CP2X_RATIO
            val cp2y = startY + h * (LAYER_NOISE_BASE + random.nextFloat() * (LAYER_NOISE_BASE * 2f))
            val endY = h * (LAYER_BASE_START_Y + (i * LAYER_STEP) + (random.nextFloat() * LAYER_NOISE_BASE))
            
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, w, endY)
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()

            val fillAlpha = LAYER_ALPHA_BASE + (i * LAYER_ALPHA_STEP)
            drawPath(
                path = path,
                color = inkColor.copy(alpha = fillAlpha)
            )
        }

        drawMotif(random, level, inkColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMotif(
    random: Random,
    level: Int,
    inkColor: Color
) {
    if (level <= 0 || level % MOTIF_SPACING != 0) return
    val motifX = size.width * (MOTIF_X_OFFSET + random.nextFloat() * MOTIF_X_RANGE)
    val motifY = size.height * (MOTIF_Y_OFFSET + random.nextFloat() * MOTIF_Y_RANGE)
    val motifColor = inkColor.copy(alpha = ALPHA_MOTIF)
    when (random.nextInt(MOTIF_COUNT)) {
        0 -> drawCircle(motifColor, MOTIF_MOON_RADIUS_DP.dp.toPx(), Offset(motifX, motifY), style = Fill)
        1 -> {
            val tw = MOTIF_TORII_WIDTH_DP.dp.toPx()
            val th = MOTIF_TORII_HEIGHT_DP.dp.toPx()
            val sw = MOTIF_TORII_STROKE_DP.dp.toPx()
            val pX1 = motifX - tw / 2f
            val pX2 = motifX + tw / 2f
            drawLine(motifColor, Offset(pX1, motifY), Offset(pX1, motifY + th), strokeWidth = sw)
            drawLine(motifColor, Offset(pX2, motifY), Offset(pX2, motifY + th), strokeWidth = sw)
            val overhang = MOTIF_TORII_TOP_BEAM_OFFSET_DP.dp.toPx()
            val beamStroke = MOTIF_TORII_BEAM_STROKE_DP.dp.toPx()
            drawLine(
                color = motifColor,
                start = Offset(pX1 - overhang, motifY + overhang),
                end = Offset(pX2 + overhang, motifY + overhang),
                strokeWidth = beamStroke
            )
            val bOffset = MOTIF_TORII_BOTTOM_BEAM_OFFSET_DP.dp.toPx()
            val bStroke = MOTIF_TORII_BOTTOM_BEAM_STROKE_DP.dp.toPx()
            drawLine(
                color = motifColor,
                start = Offset(pX1, motifY + bOffset),
                end = Offset(pX2, motifY + bOffset),
                strokeWidth = bStroke
            )
        }
        2 -> {
            val waveW = MOTIF_WAVE_SIZE_DP.dp.toPx()
            val waveH = MOTIF_WAVE_HEIGHT_DP.dp.toPx()
            val sSize = androidx.compose.ui.geometry.Size(waveW, waveH)
            val stroke = Stroke(width = MOTIF_WAVE_STROKE_DP.dp.toPx())
            for (wIdx in 0..2) {
                val dx = MOTIF_WAVE_OFFSET_X_BASE_DP.dp.toPx() + (wIdx * MOTIF_WAVE_OFFSET_X_STRIDE_DP.dp.toPx())
                val dy = wIdx * MOTIF_WAVE_OFFSET_Y_STRIDE_DP.dp.toPx()
                val tOffset = Offset(motifX - dx, motifY + dy)
                drawArc(
                    color = motifColor,
                    startAngle = MOTIF_WAVE_START_ANGLE,
                    sweepAngle = MOTIF_WAVE_SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = tOffset,
                    size = sSize,
                    style = stroke
                )
            }
        }
    }
}

@Composable
private fun SeriesVignette(series: FinishedSeriesData, index: Int, modifier: Modifier = Modifier) {
    val rotation = if (index % 2 == 0) -VIGNETTE_ROTATION_DEG else VIGNETTE_ROTATION_DEG
    Column(
        modifier = modifier
            .width(VIGNETTE_WIDTH_DP.dp)
            .rotate(rotation),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(VIGNETTE_CORNER_RADIUS_DP.dp),
            shadowElevation = VIGNETTE_ELEVATION_DP.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(VIGNETTE_IMAGE_WIDTH_DP.dp)
                .height(VIGNETTE_HEIGHT_DP.dp)
                .border(
                    VIGNETTE_BORDER_WIDTH_DP.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(VIGNETTE_CORNER_RADIUS_DP.dp)
                )
        ) {
            if (series.coverImageUrl.isNotBlank()) {
                AsyncImage(
                    model = series.coverImageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(VIGNETTE_CORNER_RADIUS_DP.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shape = RoundedCornerShape(VIGNETTE_CORNER_RADIUS_DP.dp)
        ) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = VIGNETTE_LABEL_PADDING_H_DP.dp,
                    vertical = VIGNETTE_LABEL_PADDING_V_DP.dp
                )
            )
        }
    }
}

@Composable
private fun HankoStamp(milestone: MilestoneState, modifier: Modifier = Modifier) {
    val stampColor = Color(SEAL_COLOR_VAL) // Red seal color
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(STAMP_SIZE_DP.dp)
                .border(
                    STAMP_BORDER_WIDTH_DP.dp,
                    stampColor.copy(alpha = ALPHA_SEAL),
                    RoundedCornerShape(STAMP_CORNER_RADIUS_DP.dp)
                )
                .padding(STAMP_PADDING_DP.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "証", // Just a simple kanji "proof/evidence" for the stamp aesthetic
                style = MaterialTheme.typography.labelMedium,
                color = stampColor.copy(alpha = ALPHA_SEAL)
            )
        }
        Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
        Text(
            text = milestone.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
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
