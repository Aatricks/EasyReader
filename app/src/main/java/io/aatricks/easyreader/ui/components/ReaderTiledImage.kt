package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size as CoilSize
import io.aatricks.easyreader.data.repository.content.ReaderImageTile
import io.aatricks.easyreader.ui.screens.reader.RenderPayload
import kotlin.math.roundToInt

internal fun readerImageTileCacheKey(imageUrl: String, tileIndex: Int, tileCount: Int): String =
    "$imageUrl#$tileIndex/$tileCount"

/** Display height (px) above which a strip is sliced; also the target max height of each slice. */
private const val MAX_TILE_DISPLAY_PX = 2048

/** A strip is tiled only when its on-screen height would exceed this many slices' worth. */
internal fun readerImageSliceCount(displayWidthPx: Int, srcWidth: Int, srcHeight: Int): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || displayWidthPx <= 0) return 1
    val displayHeightPx = displayWidthPx.toLong() * srcHeight / srcWidth
    return ((displayHeightPx + MAX_TILE_DISPLAY_PX - 1) / MAX_TILE_DISPLAY_PX).toInt().coerceAtLeast(1)
}

@Composable
internal fun readerImageTileView(
    tile: RenderPayload.Tile,
    backgroundColor: Color,
    onTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val request = remember(tile, screenWidthPx) {
        ImageRequest.Builder(context)
            .data(ReaderImageTile(tile.imageUrl, tile.pageUrl, tile.tileIndex, tile.tileCount))
            .memoryCacheKey(readerImageTileCacheKey(tile.imageUrl, tile.tileIndex, tile.tileCount))
            .size(CoilSize(Dimension.Pixels(screenWidthPx), Dimension.Undefined))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onTap?.invoke() }
            ),
        content = {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().background(backgroundColor),
                contentScale = ContentScale.FillBounds,
                onSuccess = { state ->
                    (state.result.image as? coil3.BitmapImage)?.bitmap?.prepareToDraw()
                }
            )
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val totalHeight = (width * tile.tileCount / tile.sliceAspect).roundToInt().coerceAtLeast(tile.tileCount)
        val start = (totalHeight.toLong() * tile.tileIndex / tile.tileCount).toInt()
        val end = (totalHeight.toLong() * (tile.tileIndex + 1) / tile.tileCount).toInt()
        val itemHeight = (end - start).coerceAtLeast(1)
        val overlap = if (tile.tileIndex < tile.tileCount - 1) 1 else 0
        val placeable = measurables.single().measure(Constraints.fixed(width, itemHeight + overlap))
        layout(width, itemHeight) {
            placeable.place(0, 0)
        }
    }
}
