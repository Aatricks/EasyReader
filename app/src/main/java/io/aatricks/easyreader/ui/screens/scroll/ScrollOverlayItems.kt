package io.aatricks.easyreader.ui.screens.scroll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.aatricks.easyreader.data.model.MilestoneState
import io.aatricks.easyreader.data.repository.FinishedSeriesData
import io.aatricks.easyreader.ui.components.rememberLibraryCoverImageRequest
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

// Vignettes (finished series), framed like hanging kakejiku
private const val VIGNETTE_FRAME_WIDTH_DP = 96f
private const val VIGNETTE_FRAME_HEIGHT_DP = 132f
private const val VIGNETTE_MAT_PADDING_DP = 5f
private const val VIGNETTE_FILLET_PADDING_DP = 1.5f
internal const val VIGNETTE_LABEL_WIDTH_DP = 128f
private const val VIGNETTE_ROTATION_DEG = 2f
private const val VIGNETTE_CORNER_DP = 3f
private const val VIGNETTE_ELEVATION_DP = 10f
private const val VIGNETTE_LABEL_PADDING_H_DP = 8f
private const val VIGNETTE_LABEL_PADDING_V_DP = 3f
internal const val CORD_WIDTH_DP = 1.2f
private const val FALLBACK_INITIAL_SP = 34
internal const val CORD_ALPHA = 0.45f
private const val TAG_ALPHA = 0.92f
private val TAG_INK = Color(0xFF3A3144)

// Hanko seals (milestones): solid vermilion, kanji knocked out in paper
private const val STAMP_SIZE_DP = 36f
private const val STAMP_ROTATION_DEG = 4f
private const val STAMP_CORNER_DP = 7f
private const val STAMP_KANJI_SP = 17
private const val STAMP_LABEL_SPACING_SP = 1.2f
private const val STAMP_LABEL_SP = 9


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


@Composable
internal fun SeriesVignette(
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
        // Kakejiku framing: washi mat around a gold fillet around the cover
        Surface(
            shape = RoundedCornerShape(VIGNETTE_CORNER_DP.dp),
            shadowElevation = VIGNETTE_ELEVATION_DP.dp,
            color = palette.sealKanji,
            modifier = Modifier.size(VIGNETTE_FRAME_WIDTH_DP.dp, VIGNETTE_FRAME_HEIGHT_DP.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(VIGNETTE_MAT_PADDING_DP.dp)
                    .background(palette.gold)
                    .padding(VIGNETTE_FILLET_PADDING_DP.dp)
                    .background(palette.frame)
            ) {
                val coverItem = series.coverItem
                if (coverItem != null) {
                    AsyncImage(
                        model = rememberLibraryCoverImageRequest(coverItem),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = series.title.take(1).uppercase(),
                            fontSize = FALLBACK_INITIAL_SP.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            color = palette.gold
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
        PaperTag(text = series.title, palette = palette)
    }
}

@Composable
internal fun HankoSeal(
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
        PaperTag(text = milestone.name.uppercase(), palette = palette, letterSpaced = true)
    }
}

@Composable
private fun PaperTag(text: String, palette: ScrollPalette, letterSpaced: Boolean = false) {
    Surface(
        color = palette.sealKanji.copy(alpha = TAG_ALPHA),
        shape = RoundedCornerShape(VIGNETTE_CORNER_DP.dp),
        shadowElevation = HAIRLINE_DP.dp
    ) {
        Text(
            text = text,
            fontSize = STAMP_LABEL_SP.sp,
            letterSpacing = if (letterSpaced) STAMP_LABEL_SPACING_SP.sp else 0.sp,
            fontWeight = FontWeight.SemiBold,
            color = TAG_INK,
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

