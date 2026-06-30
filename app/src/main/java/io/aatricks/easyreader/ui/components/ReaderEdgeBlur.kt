@file:Suppress("FunctionNaming")

package io.aatricks.easyreader.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal val supportsReaderEdgeBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private const val EDGE_BLUR_RADIUS_DP = 20f
private const val EDGE_SCRIM_ALPHA = 0.55f

@RequiresApi(Build.VERSION_CODES.S)
internal fun applyReaderEdgeBlur(graphicsLayer: GraphicsLayer, density: Density) {
    val radiusPx = with(density) { EDGE_BLUR_RADIUS_DP.dp.toPx() }
    graphicsLayer.renderEffect = RenderEffect
        .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        .asComposeRenderEffect()
}

private fun DrawScope.drawEdgeScrim(fromTop: Boolean) {
    val brush = if (fromTop) {
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = EDGE_SCRIM_ALPHA), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = EDGE_SCRIM_ALPHA)))
    }
    drawRect(brush = brush)
}

@Composable
internal fun ReaderTopEdgeBlur(graphicsLayer: GraphicsLayer, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .drawWithContent {
                if (supportsReaderEdgeBlur) {
                    drawLayer(graphicsLayer)
                }
                drawEdgeScrim(fromTop = true)
            }
    )
}

@Composable
internal fun ReaderBottomEdgeBlur(graphicsLayer: GraphicsLayer, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .drawWithContent {
                if (supportsReaderEdgeBlur) {
                    translate(top = -(graphicsLayer.size.height - size.height)) {
                        drawLayer(graphicsLayer)
                    }
                }
                drawEdgeScrim(fromTop = false)
            }
    )
}
