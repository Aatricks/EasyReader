package io.aatricks.novelscraper.ui.theme

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Color palette matching the Java app
private val Black = Color(0xFF000000)
private val DarkGrey = Color(0xFF222222)
private val White = Color(0xFFFFFFFF)
private val ColorPrimary = Color(0xFF4CAF50)
private val ColorPrimaryDark = Color(0xFF388E3C)
private val ColorAccent = Color(0xFF8BC34A)
private val SelectedItem = Color(0xFF234078)

/**
 * Dark color scheme for Novel Scraper
 * Matches the Java app's dark theme with black background
 */
private val DarkColorScheme = darkColorScheme(
    primary = ColorPrimary,
    primaryContainer = ColorPrimaryDark,
    secondary = ColorAccent,
    secondaryContainer = ColorAccent,
    tertiary = SelectedItem,
    background = Black,
    surface = DarkGrey,
    surfaceVariant = Color(0xFF2C2C2C),
    onPrimary = White,
    onSecondary = White,
    onTertiary = White,
    onBackground = White,
    onSurface = White,
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Color(0xFFFF5252),
    onError = White,
    outline = Color(0xFF444444),
    surfaceTint = ColorPrimary
)

/**
 * Light color scheme (fallback, though app is designed for dark theme)
 */
private val LightColorScheme = lightColorScheme(
    primary = ColorPrimaryDark,
    primaryContainer = ColorPrimary,
    secondary = ColorAccent,
    tertiary = Pink40,
    background = White,
    surface = Color(0xFFF5F5F5),
    onPrimary = White,
    onSecondary = White,
    onTertiary = White,
    onBackground = Black,
    onSurface = Black
)

/**
 * Novel Scraper theme with proper dark mode setup
 * 
 * @param darkTheme Whether to use dark theme (default: true, forced for reading)
 * @param dynamicColor Whether to use dynamic colors (default: false for consistent look)
 * @param content The composable content
 */
@Composable
fun NovelScraperTheme(
    darkTheme: Boolean = true, // Force dark theme for better reading experience
    dynamicColor: Boolean = false, // Disable dynamic colors for consistent design
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            
            // Set status bar color to black
            window.statusBarColor = Black.toArgb()
            
            // Set navigation bar color to black
            window.navigationBarColor = Black.toArgb()
            
            // Set light/dark icons based on theme
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = false
            windowInsetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}