package io.aatricks.easyreader.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user has turned animations off system-wide
 * (Developer options / accessibility "Remove animations").
 * Callers must skip creating the animation, not just ignore its value — an
 * `rememberInfiniteTransition` keeps recomposing its readers even when unused.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}
