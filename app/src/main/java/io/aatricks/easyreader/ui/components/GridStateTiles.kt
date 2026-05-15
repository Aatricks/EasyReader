package io.aatricks.easyreader.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

/**
 * Grid-cell-sized shimmer placeholder used while a list of items is loading.
 * Designed to slot into LazyVerticalGrid item slots; caller controls aspect via
 * the modifier. Pulses surfaceVariant to keep the silhouette but suggest motion.
 */
@Composable
fun LoadingTile(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp
) {
    val transition = rememberInfiniteTransition(label = "loading-tile-shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loading-tile-alpha"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(EasyReaderSpacing.sm))
            .background(base.copy(alpha = alpha))
    )
}

/**
 * Grid-cell-sized error placeholder with an optional retry chip. Use when a
 * source-level fetch fails (e.g., a single Explore source) so the failure is
 * surfaced inline instead of disappearing into an empty result set.
 */
@Composable
fun ErrorTile(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EasyReaderSpacing.sm))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(EasyReaderSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
            AssistChip(
                onClick = onRetry,
                label = { Text("Retry") }
            )
        }
    }
}
