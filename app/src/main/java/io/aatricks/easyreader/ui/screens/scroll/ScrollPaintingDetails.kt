package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.random.Random

// Birds
private const val BIRD_CELL_DP = 380f
private const val BIRD_SEED_SALT = 431
private const val BIRD_SKIP_CHANCE = 0.45f
private const val BIRD_COUNT_MAX = 3
private const val BIRD_Y_MIN_FRACTION = 0.12f
private const val BIRD_Y_RANGE_FRACTION = 0.2f
private const val BIRD_SPAN_DP = 10f
private const val BIRD_RISE_DP = 4f
private const val BIRD_SCATTER_DP = 34f
private const val BIRD_STROKE_DP = 1.2f
private const val BIRD_ALPHA = 0.55f

// Ridge details: pines on the near ridge, torii and lanterns on the mid ridge
private const val PINE_CELL_DP = 150f
private const val PINE_SEED_SALT = 613
private const val PINE_SKIP_CHANCE = 0.5f
private const val PINE_COUNT_MAX = 3
private const val PINE_SPACING_DP = 14f
private const val PINE_HEIGHT_MIN_DP = 16f
private const val PINE_HEIGHT_RANGE_DP = 12f
private const val PINE_WIDTH_RATIO = 0.62f
private const val PINE_TIERS = 3
private const val PINE_TIER_STEP = 0.30f
private const val PINE_TRUNK_FRACTION = 0.18f
private const val PINE_TRUNK_STROKE_DP = 1.6f
private const val TORII_CELL_DP = 760f
private const val TORII_SEED_SALT = 271
private const val TORII_SKIP_CHANCE = 0.4f
private const val TORII_HEIGHT_DP = 30f
private const val TORII_WIDTH_DP = 27f
private const val TORII_PILLAR_STROKE_DP = 2.8f
private const val TORII_BEAM_STROKE_DP = 3.6f
private const val TORII_KASAGI_LIFT_DP = 4.2f
private const val TORII_OVERHANG_DP = 5f
private const val TORII_NUKI_FRACTION = 0.38f
private const val TORII_NUKI_STROKE_DP = 2f
private const val LANTERN_CELL_DP = 520f
private const val LANTERN_SEED_SALT = 149
private const val LANTERN_SKIP_CHANCE = 0.45f
private const val LANTERN_COUNT_MAX = 3
private const val LANTERN_SPREAD_DP = 26f
private const val LANTERN_RADIUS_DP = 2.2f
private const val LANTERN_GLOW_RADIUS_DP = 9f
private const val LANTERN_GLOW_ALPHA = 0.35f
private const val LANTERN_LIFT_DP = 5f

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

internal fun DrawScope.drawBirds(palette: ScrollPalette, scrollPx: Float) {
    val cell = BIRD_CELL_DP.dp.toPx()
    val patternOffset = scrollPx * PARALLAX_BIRDS
    val firstCell = floor(patternOffset / cell).toInt() - 1
    val lastCell = floor((patternOffset + size.width) / cell).toInt() + 1
    val stroke = Stroke(width = BIRD_STROKE_DP.dp.toPx())
    val span = BIRD_SPAN_DP.dp.toPx()
    val rise = BIRD_RISE_DP.dp.toPx()
    for (index in firstCell..lastCell) {
        val random = Random(index * BIRD_SEED_SALT)
        if (random.nextFloat() < BIRD_SKIP_CHANCE) continue
        val baseX = index * cell + random.nextFloat() * cell - patternOffset
        val baseY = size.height * (BIRD_Y_MIN_FRACTION + random.nextFloat() * BIRD_Y_RANGE_FRACTION)
        repeat(1 + random.nextInt(BIRD_COUNT_MAX)) {
            val scatter = BIRD_SCATTER_DP.dp.toPx()
            val bx = baseX + (random.nextFloat() - HALF) * 2f * scatter
            val by = baseY + (random.nextFloat() - HALF) * scatter
            val path = Path()
            path.moveTo(bx - span, by)
            path.quadraticTo(bx - span / 2, by - rise, bx, by)
            path.quadraticTo(bx + span / 2, by - rise, bx + span, by)
            drawPath(path, palette.labelInk.copy(alpha = BIRD_ALPHA), style = stroke)
        }
    }
}

internal fun DrawScope.drawRidgeDetails(palette: ScrollPalette, layer: Int, patternOffset: Float) {
    if (layer == LAYER_NEAR) {
        forEachCell(PINE_CELL_DP.dp.toPx(), patternOffset, PINE_SEED_SALT) { random, x ->
            if (random.nextFloat() >= PINE_SKIP_CHANCE) {
                repeat(1 + random.nextInt(PINE_COUNT_MAX)) { i ->
                    val px = x + i * PINE_SPACING_DP.dp.toPx()
                    drawPine(palette, px, ridgeWorldY(layer, px + patternOffset), random)
                }
            }
        }
    } else {
        forEachCell(TORII_CELL_DP.dp.toPx(), patternOffset, TORII_SEED_SALT) { random, x ->
            if (random.nextFloat() >= TORII_SKIP_CHANCE) {
                drawTorii(palette, x, ridgeWorldY(layer, x + patternOffset))
            }
        }
        forEachCell(LANTERN_CELL_DP.dp.toPx(), patternOffset, LANTERN_SEED_SALT) { random, x ->
            if (random.nextFloat() >= LANTERN_SKIP_CHANCE) {
                repeat(1 + random.nextInt(LANTERN_COUNT_MAX)) {
                    val lx = x + (random.nextFloat() - HALF) * 2f * LANTERN_SPREAD_DP.dp.toPx()
                    val ly = ridgeWorldY(layer, lx + patternOffset) - LANTERN_LIFT_DP.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to palette.lantern.copy(alpha = LANTERN_GLOW_ALPHA),
                            1f to palette.lantern.copy(alpha = 0f),
                            center = Offset(lx, ly),
                            radius = LANTERN_GLOW_RADIUS_DP.dp.toPx()
                        ),
                        radius = LANTERN_GLOW_RADIUS_DP.dp.toPx(),
                        center = Offset(lx, ly)
                    )
                    drawCircle(palette.lantern, LANTERN_RADIUS_DP.dp.toPx(), Offset(lx, ly))
                }
            }
        }
    }
}

private inline fun DrawScope.forEachCell(
    cell: Float,
    patternOffset: Float,
    salt: Int,
    block: (Random, Float) -> Unit
) {
    val firstCell = floor(patternOffset / cell).toInt() - 1
    val lastCell = floor((patternOffset + size.width) / cell).toInt() + 1
    for (index in firstCell..lastCell) {
        val random = Random(index * salt)
        val x = index * cell + random.nextFloat() * cell - patternOffset
        block(random, x)
    }
}

private fun DrawScope.drawPine(palette: ScrollPalette, x: Float, groundY: Float, random: Random) {
    val height = (PINE_HEIGHT_MIN_DP + random.nextFloat() * PINE_HEIGHT_RANGE_DP).dp.toPx()
    val width = height * PINE_WIDTH_RATIO
    val trunkTop = groundY - height * PINE_TRUNK_FRACTION
    drawLine(palette.pine, Offset(x, groundY), Offset(x, trunkTop), PINE_TRUNK_STROKE_DP.dp.toPx())
    var tierTop = groundY - height
    var tierWidth = width * PINE_TIER_STEP
    repeat(PINE_TIERS) {
        val tierBottom = tierTop + height * PINE_TIER_STEP
        val path = Path()
        path.moveTo(x, tierTop)
        path.lineTo(x - tierWidth / 2, tierBottom)
        path.lineTo(x + tierWidth / 2, tierBottom)
        path.close()
        drawPath(path, palette.pine)
        tierTop = tierBottom - height * PINE_TIER_STEP * HALF
        tierWidth += width * PINE_TIER_STEP
    }
}

private fun DrawScope.drawTorii(palette: ScrollPalette, centerX: Float, groundY: Float) {
    val color = palette.vermilion
    val height = TORII_HEIGHT_DP.dp.toPx()
    val halfWidth = TORII_WIDTH_DP.dp.toPx() / 2
    val topY = groundY - height
    val pillarStroke = TORII_PILLAR_STROKE_DP.dp.toPx()
    drawLine(color, Offset(centerX - halfWidth, topY), Offset(centerX - halfWidth, groundY), pillarStroke)
    drawLine(color, Offset(centerX + halfWidth, topY), Offset(centerX + halfWidth, groundY), pillarStroke)
    val overhang = TORII_OVERHANG_DP.dp.toPx()
    val lift = TORII_KASAGI_LIFT_DP.dp.toPx()
    val kasagi = Path()
    kasagi.moveTo(centerX - halfWidth - overhang, topY - lift)
    kasagi.quadraticTo(centerX, topY + lift / 2, centerX + halfWidth + overhang, topY - lift)
    drawPath(kasagi, color, style = Stroke(width = TORII_BEAM_STROKE_DP.dp.toPx()))
    val nukiY = topY + height * TORII_NUKI_FRACTION
    drawLine(
        color,
        Offset(centerX - halfWidth - overhang / 2, nukiY),
        Offset(centerX + halfWidth + overhang / 2, nukiY),
        TORII_NUKI_STROKE_DP.dp.toPx()
    )
}

internal fun DrawScope.drawWater(palette: ScrollPalette, scrollPx: Float) {
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
    // Mirrored near and mid ridges, squashed and faded
    drawReflection(palette.ridgeNear.copy(alpha = REFLECTION_ALPHA_NEAR), LAYER_NEAR, scrollPx, waterTop)
    drawReflection(palette.ridgeMid.copy(alpha = REFLECTION_ALPHA_MID), LAYER_MID, scrollPx, waterTop)
    // Still-water shimmer strokes
    val shimmerRandom = Random(SHIMMER_SEED)
    repeat(SHIMMER_COUNT) {
        val y = waterTop + shimmerRandom.nextFloat() * (h - waterTop) * SHIMMER_BAND_FRACTION
        val width = (SHIMMER_WIDTH_MIN_DP + shimmerRandom.nextFloat() * SHIMMER_WIDTH_RANGE_DP).dp.toPx()
        val x = shimmerRandom.nextFloat() * size.width
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

