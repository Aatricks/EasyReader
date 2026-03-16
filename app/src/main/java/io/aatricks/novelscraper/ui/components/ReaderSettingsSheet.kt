package io.aatricks.novelscraper.ui.components

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.ReaderTheme
import io.aatricks.novelscraper.ui.theme.AccentTheme
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel

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
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ReaderThemeOption(
    theme: ReaderTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(theme.backgroundColor)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (theme == ReaderTheme.LIGHT || theme == ReaderTheme.SEPIA) Color.Black else Color.White
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
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(font) },
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
                .padding(EasyReaderSpacing.md),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
        ) {
            Text("Reading Settings", style = MaterialTheme.typography.titleLarge)

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
                    text = "Direction applies to paged reading only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

            SettingSlider(
                label = "Font Size",
                value = uiState.fontSize,
                onValueChange = onUpdateFontSize,
                valueRange = 12f..32f,
                steps = 19,
                displayValue = uiState.fontSize.toInt().toString()
            )

            SettingSlider(
                label = "Line Height",
                value = uiState.lineHeight,
                onValueChange = onUpdateLineHeight,
                valueRange = 1.0f..2.5f,
                steps = 14,
                displayValue = String.format("%.1f", uiState.lineHeight)
            )

            SettingSlider(
                label = "Margins",
                value = uiState.margins.toFloat(),
                onValueChange = { onUpdateMargins(it.toInt()) },
                valueRange = 0f..64f,
                steps = 15,
                displayValue = uiState.margins.toString()
            )

            SettingSlider(
                label = "Spacing",
                value = uiState.paragraphSpacing,
                onValueChange = onUpdateParagraphSpacing,
                valueRange = 0.0f..3.0f,
                steps = 29,
                displayValue = String.format("%.1f", uiState.paragraphSpacing)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("Default", "Serif", "Monospace").forEach { font ->
                    FontFamilyChip(
                        font = font,
                        isSelected = uiState.fontFamily == font,
                        onClick = { onUpdateFontFamily(font) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.xxl))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label: $displayValue", modifier = Modifier.width(100.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .scale(scaleY = 0.8f, scaleX = 1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
