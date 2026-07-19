package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Terrain: three ridge layers, far -> near, drawn as one continuous painting
private const val RIDGE_LAYERS = 3
private const val RIDGE_BASE_FRACTION = 0.46f
private const val RIDGE_LAYER_STEP = 0.16f
private const val RIDGE_AMPLITUDE_BASE = 0.09f
private const val RIDGE_AMPLITUDE_STEP = 0.035f
private const val RIDGE_ALPHA_BASE = 0.12f
private const val RIDGE_ALPHA_STEP = 0.09f
private const val RIDGE_SAMPLE_STEP_DP = 10f
private const val RIDGE_WAVELENGTH_BASE_DP = 640f
private const val RIDGE_WAVELENGTH_LAYER_DROP_DP = 110f
private const val RIDGE_SEED_SALT = 31
private const val RIDGE_HARMONICS = 3
private const val HARMONIC_WEIGHT_1 = 0.55f
private const val HARMONIC_WEIGHT_2 = 0.30f
private const val HARMONIC_WEIGHT_3 = 0.10f
private const val HARMONIC_FREQ_JITTER = 0.6f
private const val HARMONIC_FREQ_FLOOR = 0.7f
private const val TAU = (PI * 2).toFloat()

// Mist band that fades the ridges, sumi-e style
private const val MIST_TOP_FRACTION = 0.40f
private const val MIST_CENTER_FRACTION = 0.58f
private const val MIST_BOTTOM_FRACTION = 0.76f
private const val MIST_ALPHA = 0.5f

// Paper
internal const val PAPER_BLEND = 0.45f
private const val PAPER_STREAK_COUNT = 5
private const val PAPER_STREAK_ALPHA = 0.03f
private const val PAPER_STREAK_HEIGHT_DP = 1.2f
private const val MOUNTING_ALPHA = 0.22f
private const val HAIRLINE_ALPHA = 0.35f
internal const val HAIRLINE_DP = 0.8f

// Motifs, placed in the landscape on a deterministic per-segment schedule
private const val MOON_SEGMENT_MODULO = 7
private const val MOON_SEGMENT_PHASE = 2
private const val MOON_RADIUS_DP = 15f
private const val MOON_Y_FRACTION = 0.17f
private const val MOON_ALPHA = 0.16f
private const val BIRDS_SEGMENT_MODULO = 5
private const val BIRDS_SEGMENT_PHASE = 1
private const val BIRD_COUNT = 3
private const val BIRD_Y_FRACTION = 0.24f
private const val BIRD_SPAN_DP = 11f
private const val BIRD_RISE_DP = 4.5f
private const val BIRD_JITTER_DP = 26f
private const val BIRD_STROKE_DP = 1.1f
private const val BIRD_ALPHA = 0.38f
private const val TORII_SEGMENT_MODULO = 6
private const val TORII_SEGMENT_PHASE = 4
private const val TORII_HEIGHT_DP = 24f
private const val TORII_WIDTH_DP = 22f
private const val TORII_ALPHA = 0.45f
private const val TORII_PILLAR_STROKE_DP = 2.4f
private const val TORII_BEAM_STROKE_DP = 3.2f
private const val TORII_KASAGI_LIFT_DP = 3.5f
private const val TORII_OVERHANG_DP = 4f
private const val TORII_NUKI_FRACTION = 0.38f
private const val TORII_NUKI_STROKE_DP = 1.8f
private const val WAVES_SEGMENT_MODULO = 8
private const val WAVES_SEGMENT_PHASE = 6
private const val WAVE_ARC_COUNT = 3
private const val WAVE_Y_FRACTION = 0.87f
private const val WAVE_SIZE_DP = 22f
private const val WAVE_HEIGHT_DP = 9f
private const val WAVE_SHIFT_X_DP = 12f
private const val WAVE_SHIFT_Y_DP = 4f
private const val WAVE_STROKE_DP = 1.4f
private const val WAVE_ALPHA = 0.28f
private const val WAVE_ARC_START_ANGLE = 180f
private const val WAVE_ARC_SWEEP = 180f
private const val MOTIF_SEED_SALT = 131

// Roller at the far end of the scroll
private const val ROLLER_ALPHA = 0.38f
private const val ROLLER_CORNER_DP = 6f
private const val ROLLER_KNOB_INSET_DP = 3f
private const val ROLLER_KNOB_ALPHA = 0.5f

private const val END_FADE_WIDTH_DP = 150f
private const val SEGMENT_CENTER_FRACTION = 0.5f
private const val JITTER_SPREAD = 2f

/** Draws the full emakimono painting: paper, layered ridges, mist, motifs, mounting and roller. */
internal fun DrawScope.drawScrollPainting(ink: Color, paper: Color, paintingPx: Float) {
    drawPaper(paper, ink, paintingPx)
    drawRidges(ink, paper, paintingPx)
    drawMotifs(ink, paintingPx)
    drawFraming(ink)
}

private fun DrawScope.drawPaper(paper: Color, ink: Color, paintingPx: Float) {
    drawRect(paper)
    // Faint horizontal washi streaks
    val streakRandom = Random(PAPER_STREAK_COUNT)
    repeat(PAPER_STREAK_COUNT) {
        val y = streakRandom.nextFloat() * size.height
        drawRect(
            color = ink.copy(alpha = PAPER_STREAK_ALPHA),
            topLeft = Offset(0f, y),
            size = Size(paintingPx, PAPER_STREAK_HEIGHT_DP.dp.toPx())
        )
    }
}

/**
 * Height of ridge [layer] at horizontal position [x], continuous across the whole
 * painting: seeded harmonics, no per-segment reseeding, so ridges never seam.
 */
private fun DrawScope.ridgeY(layer: Int, x: Float): Float {
    val h = size.height
    val base = h * (RIDGE_BASE_FRACTION + layer * RIDGE_LAYER_STEP)
    val amplitude = h * (RIDGE_AMPLITUDE_BASE + layer * RIDGE_AMPLITUDE_STEP)
    val random = Random(layer * RIDGE_SEED_SALT + RIDGE_SEED_SALT)
    val baseWavelength = (RIDGE_WAVELENGTH_BASE_DP - layer * RIDGE_WAVELENGTH_LAYER_DROP_DP).dp.toPx()
    val weights = floatArrayOf(HARMONIC_WEIGHT_1, HARMONIC_WEIGHT_2, HARMONIC_WEIGHT_3)
    var offset = 0f
    for (k in 0 until RIDGE_HARMONICS) {
        val phase = random.nextFloat() * TAU
        val freqMul = HARMONIC_FREQ_FLOOR + random.nextFloat() * HARMONIC_FREQ_JITTER
        val frequency = TAU * (k + 1) * freqMul / baseWavelength
        offset += weights[k] * sin(x * frequency + phase)
    }
    return base + amplitude * offset
}

private fun DrawScope.drawRidges(ink: Color, paper: Color, paintingPx: Float) {
    val h = size.height
    val step = RIDGE_SAMPLE_STEP_DP.dp.toPx()
    for (layer in 0 until RIDGE_LAYERS) {
        val path = Path()
        path.moveTo(0f, ridgeY(layer, 0f))
        var x = step
        while (x < paintingPx + step) {
            val clamped = min(x, paintingPx)
            path.lineTo(clamped, ridgeY(layer, clamped))
            x += step
        }
        path.lineTo(paintingPx, h)
        path.lineTo(0f, h)
        path.close()
        drawPath(path, ink.copy(alpha = RIDGE_ALPHA_BASE + layer * RIDGE_ALPHA_STEP))
    }
    // The painting dissolves into mist where the rank end-cap begins
    val fade = END_FADE_WIDTH_DP.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            0f to paper.copy(alpha = 0f),
            1f to paper,
            startX = paintingPx - fade,
            endX = paintingPx
        ),
        topLeft = Offset(paintingPx - fade, 0f),
        size = Size(fade, h)
    )
    // Mist: paper-colored gradient band swallowing the middle distance
    drawRect(
        brush = Brush.verticalGradient(
            MIST_TOP_FRACTION to paper.copy(alpha = 0f),
            MIST_CENTER_FRACTION to paper.copy(alpha = MIST_ALPHA),
            MIST_BOTTOM_FRACTION to paper.copy(alpha = 0f)
        ),
        topLeft = Offset(0f, 0f),
        size = Size(paintingPx, h)
    )
}

private fun DrawScope.drawMotifs(ink: Color, paintingPx: Float) {
    val segments = (paintingPx / SEGMENT_WIDTH_DP.dp.toPx()).toInt()
    for (i in 0..segments) {
        val random = Random(i * MOTIF_SEED_SALT + MOTIF_SEED_SALT)
        val centerX = (i + SEGMENT_CENTER_FRACTION) * SEGMENT_WIDTH_DP.dp.toPx()
        if (centerX > paintingPx) break
        when {
            i % MOON_SEGMENT_MODULO == MOON_SEGMENT_PHASE -> drawMoon(ink, centerX)
            i % BIRDS_SEGMENT_MODULO == BIRDS_SEGMENT_PHASE -> drawBirds(ink, centerX, random)
            i % TORII_SEGMENT_MODULO == TORII_SEGMENT_PHASE -> drawTorii(ink, centerX)
            i % WAVES_SEGMENT_MODULO == WAVES_SEGMENT_PHASE -> drawWaves(ink, centerX)
        }
    }
}

private fun DrawScope.drawMoon(ink: Color, centerX: Float) {
    drawCircle(
        color = ink.copy(alpha = MOON_ALPHA),
        radius = MOON_RADIUS_DP.dp.toPx(),
        center = Offset(centerX, size.height * MOON_Y_FRACTION)
    )
}

private fun DrawScope.drawBirds(ink: Color, centerX: Float, random: Random) {
    val stroke = Stroke(width = BIRD_STROKE_DP.dp.toPx())
    val span = BIRD_SPAN_DP.dp.toPx()
    val rise = BIRD_RISE_DP.dp.toPx()
    repeat(BIRD_COUNT) {
        val jitter = BIRD_JITTER_DP.dp.toPx()
        val bx = centerX + (random.nextFloat() - SEGMENT_CENTER_FRACTION) * JITTER_SPREAD * jitter
        val by = size.height * BIRD_Y_FRACTION + (random.nextFloat() - SEGMENT_CENTER_FRACTION) * jitter
        val path = Path()
        path.moveTo(bx - span, by)
        path.quadraticTo(bx - span / 2, by - rise, bx, by)
        path.quadraticTo(bx + span / 2, by - rise, bx + span, by)
        drawPath(path, ink.copy(alpha = BIRD_ALPHA), style = stroke)
    }
}

private fun DrawScope.drawTorii(ink: Color, centerX: Float) {
    // Stands on the middle ridge so it sits in the landscape
    val groundY = ridgeY(1, centerX)
    val color = ink.copy(alpha = TORII_ALPHA)
    val height = TORII_HEIGHT_DP.dp.toPx()
    val halfWidth = TORII_WIDTH_DP.dp.toPx() / 2
    val topY = groundY - height
    val pillarStroke = TORII_PILLAR_STROKE_DP.dp.toPx()
    drawLine(color, Offset(centerX - halfWidth, topY), Offset(centerX - halfWidth, groundY), pillarStroke)
    drawLine(color, Offset(centerX + halfWidth, topY), Offset(centerX + halfWidth, groundY), pillarStroke)
    // Kasagi: top beam with a slight upward sweep at the ends
    val overhang = TORII_OVERHANG_DP.dp.toPx()
    val lift = TORII_KASAGI_LIFT_DP.dp.toPx()
    val kasagi = Path()
    kasagi.moveTo(centerX - halfWidth - overhang, topY - lift)
    kasagi.quadraticTo(centerX, topY + lift / 2, centerX + halfWidth + overhang, topY - lift)
    drawPath(kasagi, color, style = Stroke(width = TORII_BEAM_STROKE_DP.dp.toPx()))
    // Nuki: lower tie beam
    val nukiY = topY + height * TORII_NUKI_FRACTION
    drawLine(
        color,
        Offset(centerX - halfWidth - overhang / 2, nukiY),
        Offset(centerX + halfWidth + overhang / 2, nukiY),
        TORII_NUKI_STROKE_DP.dp.toPx()
    )
}

private fun DrawScope.drawWaves(ink: Color, centerX: Float) {
    val stroke = Stroke(width = WAVE_STROKE_DP.dp.toPx())
    val arcSize = Size(WAVE_SIZE_DP.dp.toPx(), WAVE_HEIGHT_DP.dp.toPx())
    repeat(WAVE_ARC_COUNT) { w ->
        val topLeft = Offset(
            centerX - arcSize.width / 2 - w * WAVE_SHIFT_X_DP.dp.toPx(),
            size.height * WAVE_Y_FRACTION + w * WAVE_SHIFT_Y_DP.dp.toPx()
        )
        drawArc(
            color = ink.copy(alpha = WAVE_ALPHA),
            startAngle = WAVE_ARC_START_ANGLE,
            sweepAngle = WAVE_ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}

private fun DrawScope.drawFraming(ink: Color) {
    val band = MOUNTING_BAND_DP.dp.toPx()
    val hairline = HAIRLINE_DP.dp.toPx()
    val bandColor = ink.copy(alpha = MOUNTING_ALPHA)
    val hairColor = ink.copy(alpha = HAIRLINE_ALPHA)
    drawRect(bandColor, topLeft = Offset(0f, 0f), size = Size(size.width, band))
    drawRect(hairColor, topLeft = Offset(0f, band), size = Size(size.width, hairline))
    drawRect(bandColor, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
    drawRect(hairColor, topLeft = Offset(0f, size.height - band - hairline), size = Size(size.width, hairline))
    val rollerWidth = ROLLER_WIDTH_DP.dp.toPx()
    val corner = ROLLER_CORNER_DP.dp.toPx()
    val inset = ROLLER_KNOB_INSET_DP.dp.toPx()
    drawRoundRect(
        color = ink.copy(alpha = ROLLER_ALPHA),
        topLeft = Offset(size.width - rollerWidth, 0f),
        size = Size(rollerWidth, size.height),
        cornerRadius = CornerRadius(corner, corner)
    )
    drawLine(
        color = ink.copy(alpha = ROLLER_KNOB_ALPHA),
        start = Offset(size.width - rollerWidth + inset, inset),
        end = Offset(size.width - rollerWidth + inset, size.height - inset),
        strokeWidth = HAIRLINE_DP.dp.toPx()
    )
}

