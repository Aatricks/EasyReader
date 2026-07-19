package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Hand-picked scroll palettes. The painting deliberately does not derive from the Material
 * color scheme: an emakimono needs indigo night, warm dawn, vermilion, and gold, in both themes.
 */
internal data class ScrollPalette(
    val skyTop: Color,
    val skyMid: Color,
    val skyHorizon: Color,
    val star: Color,
    val showStars: Boolean,
    val moon: Color,
    val moonGlow: Color,
    val ridgeFar: Color,
    val ridgeMid: Color,
    val ridgeNear: Color,
    val pine: Color,
    val mist: Color,
    val water: Color,
    val waterDeep: Color,
    val waterHighlight: Color,
    val lantern: Color,
    val vermilion: Color,
    val gold: Color,
    val frame: Color,
    val labelInk: Color,
    val sealKanji: Color,
)

private val NIGHT_PALETTE = ScrollPalette(
    skyTop = Color(0xFF141930),
    skyMid = Color(0xFF232B4C),
    skyHorizon = Color(0xFF49466E),
    star = Color(0xFFE9EDFF),
    showStars = true,
    moon = Color(0xFFF3D794),
    moonGlow = Color(0xFFF3D794),
    ridgeFar = Color(0xFF575E85),
    ridgeMid = Color(0xFF3B4268),
    ridgeNear = Color(0xFF1D2238),
    pine = Color(0xFF161A2C),
    mist = Color(0xFF49466E),
    water = Color(0xFF1A2038),
    waterDeep = Color(0xFF10142A),
    waterHighlight = Color(0xFF8B93B8),
    lantern = Color(0xFFFFB566),
    vermilion = Color(0xFFD4553F),
    gold = Color(0xFFE3C27E),
    frame = Color(0xFF0E1120),
    labelInk = Color(0xFFD9DDF2),
    sealKanji = Color(0xFFF6ECDA),
)

private val DAWN_PALETTE = ScrollPalette(
    skyTop = Color(0xFFF7EACD),
    skyMid = Color(0xFFF3DDB6),
    skyHorizon = Color(0xFFEBC79C),
    star = Color(0x00FFFFFF),
    showStars = false,
    moon = Color(0xFFE0A43C),
    moonGlow = Color(0xFFE0A43C),
    ridgeFar = Color(0xFFC0B2B4),
    ridgeMid = Color(0xFF93818F),
    ridgeNear = Color(0xFF4E4258),
    pine = Color(0xFF3A3144),
    mist = Color(0xFFEBC79C),
    water = Color(0xFFE9D5B0),
    waterDeep = Color(0xFFDDC49A),
    waterHighlight = Color(0xFFFBF3E0),
    lantern = Color(0xFFD77A3A),
    vermilion = Color(0xFFC0392B),
    gold = Color(0xFF9C7A25),
    frame = Color(0xFF3E3423),
    labelInk = Color(0xFF3E3448),
    sealKanji = Color(0xFFF6ECDA),
)

private const val DARK_LUMINANCE_THRESHOLD = 0.5f

@Composable
internal fun rememberScrollPalette(): ScrollPalette {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    return if (dark) NIGHT_PALETTE else DAWN_PALETTE
}
