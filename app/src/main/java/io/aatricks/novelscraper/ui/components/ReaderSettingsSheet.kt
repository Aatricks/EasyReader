package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.ReaderTheme
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    uiState: ReaderViewModel.ReaderUiState,
    onDismiss: () -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateLineHeight: (Float) -> Unit,
    onUpdateFontFamily: (String) -> Unit,
    onUpdateMargins: (Int) -> Unit,
    onUpdateParagraphSpacing: (Float) -> Unit,
    onUpdateReaderTheme: (ReaderTheme) -> Unit,
    sheetState: SheetState
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Reading Settings", style = MaterialTheme.typography.titleLarge)

            Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ReaderTheme.entries.forEach { theme ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(theme.backgroundColor)
                            .then(
                                if (uiState.readerTheme == theme) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                } else Modifier
                            )
                            .clickable { onUpdateReaderTheme(theme) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.readerTheme == theme) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = if (theme == ReaderTheme.LIGHT || theme == ReaderTheme.SEPIA) Color.Black else Color.White)
                        }
                    }
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
                    FilterChip(
                        selected = uiState.fontFamily == font,
                        onClick = { onUpdateFontFamily(font) },
                        label = { Text(font) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
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
            modifier = Modifier.weight(1f).scale(scaleY = 0.8f, scaleX = 1f),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}
