package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Parallax: each layer's pattern moves at its own speed relative to the finger
private const val PARALLAX_FAR = 0.35f
private const val PARALLAX_MID = 0.6f
private const val PARALLAX_NEAR = 1f
private const val PARALLAX_SKY = 0.15f

// Ridges
private const val RIDGE_SAMPLE_STEP_DP = 8f
private const val RIDGE_WAVELENGTH_BASE_DP = 620f
private const val RIDGE_WAVELENGTH_LAYER_DROP_DP = 100f
private const val RIDGE_SEED_SALT = 31
private const val RIDGE_HARMONICS = 3
private const val HARMONIC_WEIGHT_1 = 0.55f
private const val HARMONIC_WEIGHT_2 = 0.30f
private const val HARMONIC_WEIGHT_3 = 0.10f
private const val HARMONIC_FREQ_JITTER = 0.6f
private const val HARMONIC_FREQ_FLOOR = 0.7f
private const val TAU = (PI * 2).toFloat()
private const val FAR_BASE_FRACTION = 0.52f
private const val MID_BASE_FRACTION = 0.64f
private const val NEAR_BASE_FRACTION = 0.76f
private const val FAR_AMPLITUDE_FRACTION = 0.085f
private const val MID_AMPLITUDE_FRACTION = 0.075f
private const val NEAR_AMPLITUDE_FRACTION = 0.055f
private const val FAR_WASH_FRACTION = 0.6f
private const val MID_WASH_FRACTION = 0.4f
private const val NEAR_WASH_FRACTION = 0.12f

// Mist band that fades the ridges, sumi-e style
private const val MIST_TOP_FRACTION = 0.42f
private const val MIST_CENTER_FRACTION = 0.62f
private const val MIST_BOTTOM_FRACTION = 0.78f
private const val MIST_ALPHA = 0.45f

// Stars
private const val STAR_CELL_DP = 34f
private const val STAR_SEED_SALT = 977
private const val STAR_MAX_Y_FRACTION = 0.62f
private const val STAR_RADIUS_MIN_DP = 0.7f
private const val STAR_RADIUS_RANGE_DP = 1.6f
private const val STAR_ALPHA_MIN = 0.25f
private const val STAR_ALPHA_RANGE = 0.75f
private const val STAR_SKIP_CHANCE = 0.18f
private const val STAR_BRIGHT_CHANCE = 0.1f
private const val STAR_GLINT_LENGTH_DP = 4.5f
private const val STAR_GLINT_STROKE_DP = 0.7f
private const val STAR_GLINT_ALPHA = 0.5f
private const val TWINKLE_DEPTH = 0.45f
private const val TWINKLE_CYCLES = 2f

// Moon, anchored near the journey's end
internal const val MOON_PARALLAX = 0.3f
internal const val MOON_TARGET_VIEWPORT_FRACTION = 0.42f
private const val MOON_Y_FRACTION = 0.18f
private const val MOON_RADIUS_DP = 22f
private const val MOON_GLOW_RADIUS_DP = 78f
private const val MOON_GLOW_ALPHA = 0.4f
private const val MOON_INNER_GLOW_ALPHA = 0.16f
private const val MOON_BREATH_DEPTH = 0.18f

// Water
internal const val WATER_TOP_FRACTION = 0.8f
private const val REFLECTION_ALPHA_NEAR = 0.22f
private const val REFLECTION_ALPHA_MID = 0.10f
private const val REFLECTION_SQUASH = 0.55f
private const val SHIMMER_COUNT = 7
private const val SHIMMER_SEED = 353
private const val SHIMMER_WIDTH_MIN_DP = 26f
private const val SHIMMER_WIDTH_RANGE_DP = 60f
private const val SHIMMER_STROKE_DP = 1f
private const val SHIMMER_ALPHA = 0.2f
private const val SHIMMER_BAND_FRACTION = 0.6f
private const val SHIMMER_DRIFT_FRACTION = 0.06f

// Framing
private const val MOUNTING_ALPHA = 0.92f
private const val HAIRLINE_GOLD_ALPHA = 0.5f
private const val ROLLER_CORNER_DP = 6f
private const val ROLLER_KNOB_INSET_DP = 3f
private const val ROLLER_KNOB_ALPHA = 0.5f

internal const val HALF = 0.5f

internal const val LAYER_FAR = 0
internal const val LAYER_MID = 1
internal const val LAYER_NEAR = 2

/**
 * Draws the emakimono as seen through the viewport: parallax sky and moon over three ridge
 * layers, still water with reflections, and the scroll framing. [scrollPx] is the 1x scroll
 * offset; [motion] loops 0..1 and drives the star twinkle, moon breathing, and water shimmer.
 */
internal fun DrawScope.drawScrollPainting(
    palette: ScrollPalette,
    scrollPx: Float,
    totalPx: Float,
    motion: Float
) {
    drawSkyAndStars(palette, scrollPx, motion)
    drawMoon(palette, scrollPx, totalPx, motion)
    drawRidgeLayer(palette, LAYER_FAR, scrollPx)
    drawRidgeLayer(palette, LAYER_MID, scrollPx)
    drawRidgeLayer(palette, LAYER_NEAR, scrollPx)
    drawWater(palette, scrollPx, motion)
    drawFraming(palette)
}

private fun DrawScope.drawSkyAndStars(palette: ScrollPalette, scrollPx: Float, motion: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to palette.skyTop,
            HALF to palette.skyMid,
            1f to palette.skyHorizon
        )
    )
    if (!palette.showStars) return
    val cell = STAR_CELL_DP.dp.toPx()
    val patternOffset = scrollPx * PARALLAX_SKY
    val firstCell = floor(patternOffset / cell).toInt() - 1
    val lastCell = floor((patternOffset + size.width) / cell).toInt() + 1
    for (index in firstCell..lastCell) {
        val random = Random(index * STAR_SEED_SALT)
        if (random.nextFloat() < STAR_SKIP_CHANCE) continue
        val x = index * cell + random.nextFloat() * cell - patternOffset
        val y = random.nextFloat() * size.height * STAR_MAX_Y_FRACTION
        val radius = (STAR_RADIUS_MIN_DP + random.nextFloat() * STAR_RADIUS_RANGE_DP).dp.toPx()
        val baseAlpha = STAR_ALPHA_MIN + random.nextFloat() * STAR_ALPHA_RANGE
        val bright = random.nextFloat() < STAR_BRIGHT_CHANCE
        // Only the bright stars twinkle; the field stays calm
        val twinkle = if (bright) {
            1f - TWINKLE_DEPTH * (HALF + HALF * sin((motion * TWINKLE_CYCLES + random.nextFloat()) * TAU))
        } else {
            1f
        }
        drawCircle(palette.star.copy(alpha = baseAlpha * twinkle), radius, Offset(x, y))
        if (bright) {
            val glint = STAR_GLINT_LENGTH_DP.dp.toPx()
            val stroke = STAR_GLINT_STROKE_DP.dp.toPx()
            val glintColor = palette.star.copy(alpha = STAR_GLINT_ALPHA * twinkle)
            drawLine(glintColor, Offset(x - glint, y), Offset(x + glint, y), stroke)
            drawLine(glintColor, Offset(x, y - glint), Offset(x, y + glint), stroke)
        }
    }
}

/** The moon rests just left of the end-cap when the scroll is opened at the journey's end. */
private fun DrawScope.drawMoon(palette: ScrollPalette, scrollPx: Float, totalPx: Float, motion: Float) {
    val scrollMax = (totalPx - size.width).coerceAtLeast(0f)
    val pattern = size.width * MOON_TARGET_VIEWPORT_FRACTION + scrollMax * MOON_PARALLAX
    val x = pattern - scrollPx * MOON_PARALLAX
    if (x < -MOON_GLOW_RADIUS_DP.dp.toPx() || x > size.width + MOON_GLOW_RADIUS_DP.dp.toPx()) return
    val center = Offset(x, size.height * MOON_Y_FRACTION)
    val glowRadius = MOON_GLOW_RADIUS_DP.dp.toPx()
    val breath = 1f - MOON_BREATH_DEPTH * (HALF + HALF * sin(motion * TAU))
    drawCircle(
        brush = Brush.radialGradient(
            0f to palette.moonGlow.copy(alpha = MOON_GLOW_ALPHA * breath),
            HALF to palette.moonGlow.copy(alpha = MOON_INNER_GLOW_ALPHA * breath),
            1f to palette.moonGlow.copy(alpha = 0f),
            center = center,
            radius = glowRadius
        ),
        radius = glowRadius,
        center = center
    )
    drawCircle(palette.moon, MOON_RADIUS_DP.dp.toPx(), center)
}

internal fun layerParams(layer: Int): Triple<Float, Float, Float> = when (layer) {
    LAYER_FAR -> Triple(PARALLAX_FAR, FAR_BASE_FRACTION, FAR_AMPLITUDE_FRACTION)
    LAYER_MID -> Triple(PARALLAX_MID, MID_BASE_FRACTION, MID_AMPLITUDE_FRACTION)
    else -> Triple(PARALLAX_NEAR, NEAR_BASE_FRACTION, NEAR_AMPLITUDE_FRACTION)
}

/**
 * Ridge height at pattern coordinate [patternX], continuous everywhere: seeded harmonics,
 * no per-segment reseeding, so ridges never seam at any scroll position.
 */
internal fun DrawScope.ridgeWorldY(layer: Int, patternX: Float): Float {
    val (_, baseFraction, amplitudeFraction) = layerParams(layer)
    val random = Random(layer * RIDGE_SEED_SALT + RIDGE_SEED_SALT)
    val baseWavelength = (RIDGE_WAVELENGTH_BASE_DP - layer * RIDGE_WAVELENGTH_LAYER_DROP_DP).dp.toPx()
    val weights = floatArrayOf(HARMONIC_WEIGHT_1, HARMONIC_WEIGHT_2, HARMONIC_WEIGHT_3)
    var offset = 0f
    for (k in 0 until RIDGE_HARMONICS) {
        val phase = random.nextFloat() * TAU
        val freqMul = HARMONIC_FREQ_FLOOR + random.nextFloat() * HARMONIC_FREQ_JITTER
        val frequency = TAU * (k + 1) * freqMul / baseWavelength
        offset += weights[k] * sin(patternX * frequency + phase)
    }
    return size.height * baseFraction + size.height * amplitudeFraction * offset
}

private fun DrawScope.drawRidgeLayer(palette: ScrollPalette, layer: Int, scrollPx: Float) {
    val (parallax, baseFraction, amplitudeFraction) = layerParams(layer)
    val color = when (layer) {
        LAYER_FAR -> palette.ridgeFar
        LAYER_MID -> palette.ridgeMid
        else -> palette.ridgeNear
    }
    val h = size.height
    val step = RIDGE_SAMPLE_STEP_DP.dp.toPx()
    val patternOffset = scrollPx * parallax
    val path = Path()
    path.moveTo(0f, ridgeWorldY(layer, patternOffset))
    var x = step
    while (x < size.width + step) {
        path.lineTo(min(x, size.width), ridgeWorldY(layer, min(x, size.width) + patternOffset))
        x += step
    }
    path.lineTo(size.width, h)
    path.lineTo(0f, h)
    path.close()
    val wash = when (layer) {
        LAYER_FAR -> FAR_WASH_FRACTION
        LAYER_MID -> MID_WASH_FRACTION
        else -> NEAR_WASH_FRACTION
    }
    val crestY = h * (baseFraction - amplitudeFraction)
    drawPath(
        path,
        brush = Brush.verticalGradient(
            0f to color,
            1f to lerp(color, palette.mist, wash),
            startY = crestY,
            endY = h
        )
    )
    if (layer == LAYER_MID) {
        drawRect(
            brush = Brush.verticalGradient(
                MIST_TOP_FRACTION to palette.mist.copy(alpha = 0f),
                MIST_CENTER_FRACTION to palette.mist.copy(alpha = MIST_ALPHA),
                MIST_BOTTOM_FRACTION to palette.mist.copy(alpha = 0f)
            )
        )
    }
}

private fun DrawScope.drawWater(palette: ScrollPalette, scrollPx: Float, motion: Float) {
    val h = size.height
    val waterTop = h * WATER_TOP_FRACTION
    drawRect(
        brush = Brush.verticalGradient(
            0f to palette.water,
            1f to palette.waterDeep,
            startY = waterTop,
            endY = h
        ),
        topLeft = Offset(0f, waterTop),
        size = Size(size.width, h - waterTop)
    )
    drawReflection(palette.ridgeNear.copy(alpha = REFLECTION_ALPHA_NEAR), LAYER_NEAR, scrollPx, waterTop)
    drawReflection(palette.ridgeMid.copy(alpha = REFLECTION_ALPHA_MID), LAYER_MID, scrollPx, waterTop)
    // Still-water shimmer strokes, drifting slowly
    val shimmerRandom = Random(SHIMMER_SEED)
    val drift = motion * size.width * SHIMMER_DRIFT_FRACTION
    repeat(SHIMMER_COUNT) {
        val y = waterTop + shimmerRandom.nextFloat() * (h - waterTop) * SHIMMER_BAND_FRACTION
        val width = (SHIMMER_WIDTH_MIN_DP + shimmerRandom.nextFloat() * SHIMMER_WIDTH_RANGE_DP).dp.toPx()
        val x = (shimmerRandom.nextFloat() * size.width + drift).mod(size.width)
        drawLine(
            palette.waterHighlight.copy(alpha = SHIMMER_ALPHA),
            Offset(x - width / 2, y),
            Offset(x + width / 2, y),
            SHIMMER_STROKE_DP.dp.toPx()
        )
    }
}

private fun DrawScope.drawReflection(color: Color, layer: Int, scrollPx: Float, waterTop: Float) {
    val (parallax, _, _) = layerParams(layer)
    val step = RIDGE_SAMPLE_STEP_DP.dp.toPx() * 2
    val patternOffset = scrollPx * parallax
    val path = Path()
    path.moveTo(0f, waterTop + (waterTop - ridgeWorldY(layer, patternOffset)) * REFLECTION_SQUASH)
    var x = step
    while (x < size.width + step) {
        val ridge = ridgeWorldY(layer, x + patternOffset)
        path.lineTo(x, waterTop + (waterTop - ridge) * REFLECTION_SQUASH)
        x += step
    }
    path.lineTo(size.width, waterTop)
    path.lineTo(0f, waterTop)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawFraming(palette: ScrollPalette) {
    val band = MOUNTING_BAND_DP.dp.toPx()
    val hairline = HAIRLINE_DP.dp.toPx()
    val bandColor = palette.frame.copy(alpha = MOUNTING_ALPHA)
    val goldLine = palette.gold.copy(alpha = HAIRLINE_GOLD_ALPHA)
    drawRect(bandColor, topLeft = Offset(0f, 0f), size = Size(size.width, band))
    drawRect(goldLine, topLeft = Offset(0f, band), size = Size(size.width, hairline))
    drawRect(bandColor, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
    drawRect(goldLine, topLeft = Offset(0f, size.height - band - hairline), size = Size(size.width, hairline))
    val rollerWidth = ROLLER_WIDTH_DP.dp.toPx()
    val corner = ROLLER_CORNER_DP.dp.toPx()
    val inset = ROLLER_KNOB_INSET_DP.dp.toPx()
    drawRoundRect(
        color = palette.frame,
        topLeft = Offset(size.width - rollerWidth, 0f),
        size = Size(rollerWidth, size.height),
        cornerRadius = CornerRadius(corner, corner)
    )
    drawLine(
        color = palette.gold.copy(alpha = ROLLER_KNOB_ALPHA),
        start = Offset(size.width - rollerWidth + inset, inset),
        end = Offset(size.width - rollerWidth + inset, size.height - inset),
        strokeWidth = hairline
    )
}
