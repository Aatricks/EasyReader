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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.aatricks.easyreader.ui.util.toFontFamily
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.R
import io.aatricks.easyreader.data.model.ReaderTheme
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlin.math.roundToInt

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
            .semantics {
                contentDescription = "Theme ${theme.name.lowercase()}"
                role = Role.Button
                selected = isSelected
            },
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
private fun FontFamilyChip(
    font: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val fontFamily = font.toFontFamily()
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
    onUpdateVerticalMargins: (Int) -> Unit,
    onUpdateParagraphSpacing: (Float) -> Unit,
    onUpdateBrightness: (Float) -> Unit,
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
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
        ) {
            Text(
                stringResource(R.string.reader_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel(stringResource(R.string.reader_settings_section_layout))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    FilterChip(
                        selected = !uiState.isPagedMode,
                        onClick = { onUpdatePagedMode(false) },
                        label = { Text(stringResource(R.string.reader_layout_scroll)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                    FilterChip(
                        selected = uiState.isPagedMode,
                        onClick = { onUpdatePagedMode(true) },
                        label = { Text(stringResource(R.string.reader_layout_paged)) },
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
                SettingsSectionLabel(stringResource(R.string.reader_settings_section_direction))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                ) {
                    FilterChip(
                        selected = !uiState.isRtl,
                        onClick = { onUpdateRtl(false) },
                        enabled = uiState.isPagedMode,
                        label = { Text(stringResource(R.string.reader_direction_ltr)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                    FilterChip(
                        selected = uiState.isRtl,
                        onClick = { onUpdateRtl(true) },
                        enabled = uiState.isPagedMode,
                        label = { Text(stringResource(R.string.reader_direction_rtl)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsChipColors()
                    )
                }
                if (!uiState.isPagedMode) {
                    Text(
                        text = stringResource(R.string.reader_direction_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel(stringResource(R.string.reader_settings_section_theme))
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


            SettingSlider(
                label = stringResource(R.string.reader_slider_brightness),
                value = uiState.brightness,
                onValueChange = onUpdateBrightness,
                valueRange = 0.1f..1.0f,
                steps = 8,
                displayValue = "${(uiState.brightness * 100).roundToInt()}%"
            )

            SettingSlider(
                label = stringResource(R.string.reader_slider_font_size),
                value = uiState.fontSize,
                onValueChange = onUpdateFontSize,
                valueRange = 12f..32f,
                steps = 19,
                displayValue = "${uiState.fontSize.toInt()} sp"
            )

            SettingSlider(
                label = stringResource(R.string.reader_slider_line_height),
                value = uiState.lineHeight,
                onValueChange = onUpdateLineHeight,
                valueRange = 1.0f..2.5f,
                steps = 14,
                displayValue = "${String.format("%.1f", uiState.lineHeight)}×"
            )

            SettingSlider(
                label = stringResource(R.string.reader_slider_margins),
                value = uiState.margins.toFloat(),
                onValueChange = { onUpdateMargins(it.toInt()) },
                valueRange = 4f..64f,
                steps = 14,
                displayValue = "${uiState.margins} dp"
            )

            SettingSlider(
                label = stringResource(R.string.reader_slider_vertical_margins),
                value = uiState.verticalMargins.toFloat(),
                onValueChange = { onUpdateVerticalMargins(it.toInt()) },
                valueRange = 0f..160f,
                steps = 15,
                displayValue = "${uiState.verticalMargins} dp"
            )

            SettingSlider(
                label = stringResource(R.string.reader_slider_paragraph_spacing),
                value = uiState.paragraphSpacing,
                onValueChange = onUpdateParagraphSpacing,
                valueRange = 0.0f..3.0f,
                steps = 29,
                displayValue = "${String.format("%.1f", uiState.paragraphSpacing)}×"
            )

            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                SettingsSectionLabel(stringResource(R.string.reader_settings_section_font))
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
                val previewFont = uiState.fontFamily.toFontFamily()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = stringResource(R.string.reader_preview_pangram),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = EasyReaderSpacing.sm),
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
