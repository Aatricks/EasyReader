package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.data.model.ReaderTheme
import io.aatricks.easyreader.ui.theme.AccentTheme
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel

@Composable
private fun settingsChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ReaderThemeOption(
    theme: ReaderTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isLightSurface = theme == ReaderTheme.LIGHT || theme == ReaderTheme.SEPIA
    val checkColor = if (isLightSurface) Color.Black else Color.White
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isSelected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(theme.backgroundColor)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Theme ${theme.name.lowercase()}" },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            // Show subtle Aa preview text in unselected swatches so users see how it'll look
            Text(
                text = "Aa",
                color = checkColor.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun AccentThemeChip(
    accentTheme: AccentTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(accentTheme.displayName) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accentTheme.previewColor)
            )
        },
        colors = settingsChipColors()
    )
}

@Composable
private fun FontFamilyChip(
    font: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val fontFamily = when (font) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = font,
                fontFamily = fontFamily
            )
        },
        colors = settingsChipColors()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    uiState: ReaderViewModel.ReaderUiState,
    onDismiss: () -> Unit,
    onUpdatePagedMode: (Boolean) -> Unit,
    onUpdateRtl: (Boolean) -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateLineHeight: (Float) -> Unit,
    onUpdateFontFamily: (String) -> Unit,
    onUpdateMargins: (Int) -> Unit,
    onUpdateParagraphSpacing: (Float) -> Unit,
    onUpdateReaderTheme: (ReaderTheme) -> Unit,
    onUpdateAccentTheme: (AccentTheme) -> Unit,
    sheetState: SheetState
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
        ) {
            Text(
                "Reading Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel("Layout")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    FilterChip(
                        selected = !uiState.isPagedMode,
                        onClick = { onUpdatePagedMode(false) },
                        label = { Text("Scroll") },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                    FilterChip(
                        selected = uiState.isPagedMode,
                        onClick = { onUpdatePagedMode(true) },
                        label = { Text("Paged") },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                }
                Text(
                    text = if (uiState.isPagedMode)
                        "Swipe horizontally to turn pages."
                    else
                        "Scroll vertically to read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel("Direction")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    FilterChip(
                        selected = !uiState.isRtl,
                        onClick = { onUpdateRtl(false) },
                        enabled = uiState.isPagedMode,
                        label = { Text("LTR") },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                    FilterChip(
                        selected = uiState.isRtl,
                        onClick = { onUpdateRtl(true) },
                        enabled = uiState.isPagedMode,
                        label = { Text("RTL") },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                }
                if (!uiState.isPagedMode) {
                    Text(
                        text = "Switch Layout to Paged to change reading direction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel("Theme")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ReaderTheme.entries.forEach { theme ->
                        ReaderThemeOption(
                            theme = theme,
                            isSelected = uiState.readerTheme == theme,
                            onClick = { onUpdateReaderTheme(theme) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel("Accent")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    AccentTheme.entries.forEach { accentTheme ->
                        AccentThemeChip(
                            accentTheme = accentTheme,
                            isSelected = uiState.accentTheme == accentTheme,
                            onClick = { onUpdateAccentTheme(accentTheme) }
                        )
                    }
                }
            }

            SettingSlider(
                label = "Font size",
                value = uiState.fontSize,
                onValueChange = onUpdateFontSize,
                valueRange = 12f..32f,
                steps = 19,
                displayValue = "${uiState.fontSize.toInt()} sp"
            )

            SettingSlider(
                label = "Line height",
                value = uiState.lineHeight,
                onValueChange = onUpdateLineHeight,
                valueRange = 1.0f..2.5f,
                steps = 14,
                displayValue = "${String.format("%.1f", uiState.lineHeight)}×"
            )

            SettingSlider(
                label = "Margins",
                value = uiState.margins.toFloat(),
                onValueChange = { onUpdateMargins(it.toInt()) },
                valueRange = 4f..64f,
                steps = 14,
                displayValue = "${uiState.margins} dp"
            )

            SettingSlider(
                label = "Paragraph spacing",
                value = uiState.paragraphSpacing,
                onValueChange = onUpdateParagraphSpacing,
                valueRange = 0.0f..3.0f,
                steps = 29,
                displayValue = "${String.format("%.1f", uiState.paragraphSpacing)}×"
            )

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel("Font")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    listOf("Default", "Serif", "Monospace").forEach { font ->
                        FontFamilyChip(
                            font = font,
                            isSelected = uiState.fontFamily == font,
                            onClick = { onUpdateFontFamily(font) }
                        )
                    }
                }
                val previewFont = when (uiState.fontFamily) {
                    "Serif" -> FontFamily.Serif
                    "Monospace" -> FontFamily.Monospace
                    else -> FontFamily.Default
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "The quick brown fox jumps over the lazy dog.",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = previewFont
                    )
                }
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
        }
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .semantics { contentDescription = "$label, $displayValue" },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
