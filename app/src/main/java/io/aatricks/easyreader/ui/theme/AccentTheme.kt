package io.aatricks.easyreader.ui.theme

import androidx.compose.ui.graphics.Color

internal data class AccentPalette(
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val tertiary: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    val onSecondary: Color,
    val onSecondaryContainer: Color,
    val onTertiary: Color
)

enum class AccentTheme(
    val displayName: String,
    val previewColor: Color,
    internal val darkPalette: AccentPalette,
    internal val lightPalette: AccentPalette
) {
    MOSS(
        displayName = "Moss",
        previewColor = Color(0xFF95BC74),
        darkPalette = AccentPalette(
            primary = Color(0xFF95BC74),
            primaryContainer = Color(0xFF23311A),
            secondary = Color(0xFFADC39C),
            secondaryContainer = Color(0xFF2B352B),
            tertiary = Color(0xFF879DB8),
            onPrimary = Color(0xFF11200A),
            onPrimaryContainer = Color(0xFFDCEACF),
            onSecondary = Color(0xFF162113),
            onSecondaryContainer = Color(0xFFD8E7CF),
            onTertiary = Color(0xFF142131)
        ),
        lightPalette = AccentPalette(
            primary = Color(0xFF4E6C3C),
            primaryContainer = Color(0xFFD6E7C6),
            secondary = Color(0xFF62785A),
            secondaryContainer = Color(0xFFDDE7D8),
            tertiary = Color(0xFF5F7694),
            onPrimary = Color.White,
            onPrimaryContainer = Color(0xFF15210F),
            onSecondary = Color.White,
            onSecondaryContainer = Color(0xFF162113),
            onTertiary = Color.White
        )
    ),
    OCEAN(
        displayName = "Ocean",
        previewColor = Color(0xFF83B8D8),
        darkPalette = AccentPalette(
            primary = Color(0xFF83B8D8),
            primaryContainer = Color(0xFF163041),
            secondary = Color(0xFFA7C7DA),
            secondaryContainer = Color(0xFF233641),
            tertiary = Color(0xFFC7B58E),
            onPrimary = Color(0xFF0B2030),
            onPrimaryContainer = Color(0xFFD7ECF7),
            onSecondary = Color(0xFF10232D),
            onSecondaryContainer = Color(0xFFD7E8F0),
            onTertiary = Color(0xFF2D220E)
        ),
        lightPalette = AccentPalette(
            primary = Color(0xFF346A8A),
            primaryContainer = Color(0xFFD4E7F2),
            secondary = Color(0xFF597486),
            secondaryContainer = Color(0xFFDCE7EE),
            tertiary = Color(0xFF876C43),
            onPrimary = Color.White,
            onPrimaryContainer = Color(0xFF102332),
            onSecondary = Color.White,
            onSecondaryContainer = Color(0xFF18222B),
            onTertiary = Color.White
        )
    ),
    AMBER(
        displayName = "Amber",
        previewColor = Color(0xFFD9B36F),
        darkPalette = AccentPalette(
            primary = Color(0xFFD9B36F),
            primaryContainer = Color(0xFF3B2B12),
            secondary = Color(0xFFD7C29A),
            secondaryContainer = Color(0xFF3A3221),
            tertiary = Color(0xFFA5BA83),
            onPrimary = Color(0xFF271700),
            onPrimaryContainer = Color(0xFFF6E7CA),
            onSecondary = Color(0xFF221A0C),
            onSecondaryContainer = Color(0xFFF1E3C6),
            onTertiary = Color(0xFF162109)
        ),
        lightPalette = AccentPalette(
            primary = Color(0xFF8A6427),
            primaryContainer = Color(0xFFF0DEB9),
            secondary = Color(0xFF7E6B4E),
            secondaryContainer = Color(0xFFEDE3D1),
            tertiary = Color(0xFF667A48),
            onPrimary = Color.White,
            onPrimaryContainer = Color(0xFF251800),
            onSecondary = Color.White,
            onSecondaryContainer = Color(0xFF271F10),
            onTertiary = Color.White
        )
    ),
    ROSE(
        displayName = "Rose",
        previewColor = Color(0xFFD8A1A2),
        darkPalette = AccentPalette(
            primary = Color(0xFFD8A1A2),
            primaryContainer = Color(0xFF402022),
            secondary = Color(0xFFD5B8A8),
            secondaryContainer = Color(0xFF3B2D2A),
            tertiary = Color(0xFF93B59A),
            onPrimary = Color(0xFF311416),
            onPrimaryContainer = Color(0xFFF6D8D8),
            onSecondary = Color(0xFF261815),
            onSecondaryContainer = Color(0xFFF1DED6),
            onTertiary = Color(0xFF112017)
        ),
        lightPalette = AccentPalette(
            primary = Color(0xFF8A5457),
            primaryContainer = Color(0xFFF0D6D7),
            secondary = Color(0xFF7B6357),
            secondaryContainer = Color(0xFFECDDD4),
            tertiary = Color(0xFF58745F),
            onPrimary = Color.White,
            onPrimaryContainer = Color(0xFF301517),
            onSecondary = Color.White,
            onSecondaryContainer = Color(0xFF271A16),
            onTertiary = Color.White
        )
    )
}
