package io.aatricks.easyreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PineNight = Color(0xFF0E1210)
private val ForestSurface = Color(0xFF171C19)
private val ForestSurfaceVariant = Color(0xFF212823)
private val Mist = Color(0xFFF4F5EF)
private val Redwood = Color(0xFFE69587)
private val OutlineTint = Color(0xFF414B44)
private val OutlineVariantTint = Color(0xFF2F3832)

private fun buildDarkColorScheme(accentTheme: AccentTheme) = darkColorScheme(
    primary = accentTheme.darkPalette.primary,
    primaryContainer = accentTheme.darkPalette.primaryContainer,
    secondary = accentTheme.darkPalette.secondary,
    secondaryContainer = accentTheme.darkPalette.secondaryContainer,
    tertiary = accentTheme.darkPalette.tertiary,
    background = PineNight,
    surface = ForestSurface,
    surfaceVariant = ForestSurfaceVariant,
    onPrimary = accentTheme.darkPalette.onPrimary,
    onPrimaryContainer = accentTheme.darkPalette.onPrimaryContainer,
    onSecondary = accentTheme.darkPalette.onSecondary,
    onSecondaryContainer = accentTheme.darkPalette.onSecondaryContainer,
    onTertiary = accentTheme.darkPalette.onTertiary,
    onBackground = Mist,
    onSurface = Mist,
    onSurfaceVariant = Color(0xFFC8CEC7),
    error = Redwood,
    onError = Color(0xFF30110B),
    outline = OutlineTint,
    outlineVariant = OutlineVariantTint,
    surfaceTint = accentTheme.darkPalette.primary
)

private fun buildLightColorScheme(accentTheme: AccentTheme) = lightColorScheme(
    primary = accentTheme.lightPalette.primary,
    primaryContainer = accentTheme.lightPalette.primaryContainer,
    secondary = accentTheme.lightPalette.secondary,
    secondaryContainer = accentTheme.lightPalette.secondaryContainer,
    tertiary = accentTheme.lightPalette.tertiary,
    background = Color(0xFFF6F4EE),
    surface = Color(0xFFFCFAF5),
    surfaceVariant = Color(0xFFE6E7DE),
    onPrimary = accentTheme.lightPalette.onPrimary,
    onPrimaryContainer = accentTheme.lightPalette.onPrimaryContainer,
    onSecondary = accentTheme.lightPalette.onSecondary,
    onSecondaryContainer = accentTheme.lightPalette.onSecondaryContainer,
    onTertiary = accentTheme.lightPalette.onTertiary,
    onBackground = Color(0xFF171C19),
    onSurface = Color(0xFF171C19),
    onSurfaceVariant = Color(0xFF525A54),
    error = Color(0xFFB34E3E),
    onError = Color.White,
    outline = Color(0xFF747C75),
    outlineVariant = Color(0xFFCDD3CA)
)

@Composable
fun NovelScraperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentTheme: AccentTheme = AccentTheme.MOSS,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> buildDarkColorScheme(accentTheme)
        else -> buildLightColorScheme(accentTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = EasyReaderShapes,
        content = content
    )
}
