package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size as CoilSize
import io.aatricks.easyreader.data.repository.content.ReaderImageTile

/** Display height (px) above which a strip is sliced; also the target max height of each slice. */
private const val MAX_TILE_DISPLAY_PX = 2048

/** A strip is tiled only when its on-screen height would exceed this many slices' worth. */
internal fun readerImageSliceCount(displayWidthPx: Int, srcWidth: Int, srcHeight: Int): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || displayWidthPx <= 0) return 1
    val displayHeightPx = displayWidthPx.toLong() * srcHeight / srcWidth
    return ((displayHeightPx + MAX_TILE_DISPLAY_PX - 1) / MAX_TILE_DISPLAY_PX).toInt().coerceAtLeast(1)
}

/**
 * Renders a tall web strip as [sliceCount] vertically-stacked, region-decoded slices. Each slice is
 * a texture-sized hardware bitmap, so only the 1–2 on-screen slices pay any draw cost — eliminating
 * the software-render stall a single over-tall bitmap causes during fast scrolling. Slices are
 * pre-sized via [aspectRatio] from the known dimensions, so there is no placeholder collapse.
 */
@Composable
fun ReaderTiledImage(
    imageUrl: String,
    pageUrl: String,
    sliceAspect: Float,
    sliceCount: Int,
    backgroundColor: Color,
    onTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onTap?.invoke() }
            )
    ) {
        repeat(sliceCount) { index ->
            val request = remember(imageUrl, pageUrl, index, sliceCount) {
                ImageRequest.Builder(context)
                    .data(ReaderImageTile(imageUrl, pageUrl, index, sliceCount))
                    .memoryCacheKey("$imageUrl#$index/$sliceCount")
                    .size(CoilSize(Dimension.Pixels(screenWidthPx), Dimension.Undefined))
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(sliceAspect)
                    .background(backgroundColor),
                contentScale = ContentScale.FillWidth,
                // Upload the slice texture asynchronously as soon as it decodes (all slices of an
                // item compose together, so off-screen ones get uploaded ahead of being drawn).
                onSuccess = { state ->
                    (state.result.image as? coil3.BitmapImage)?.bitmap?.prepareToDraw()
                }
            )
        }
    }
}
