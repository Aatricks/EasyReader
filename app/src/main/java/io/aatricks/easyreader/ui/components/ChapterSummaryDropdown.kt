package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

@Composable
fun ChapterSummaryDropdown(
    summary: String?,
    isGenerating: Boolean,
    onGenerateSummary: () -> Unit,
    onCancel: (() -> Unit)? = null,
    aiSupportedInBuild: Boolean = true,
    aiOptedIn: Boolean = true,
    onEnableAi: (() -> Unit)? = null,
    isInitializing: Boolean = false,
    isReady: Boolean = aiSupportedInBuild && aiOptedIn,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Chapter summary",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))

                when {
                    !aiSupportedInBuild -> {
                        Text(
                            text = "AI summaries aren't available in this build.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                        Text(
                            text = "Install the AI variant to enable on-device chapter recaps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    !aiOptedIn -> {
                        Text(
                            text = "Enable AI summaries to generate on-device chapter recaps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                        Text(
                            text = "The AI model is downloaded once (a few hundred MB) and then runs offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onEnableAi != null) {
                            FilledTonalButton(onClick = onEnableAi) {
                                Text("Enable AI summaries")
                            }
                        }
                    }

                    isInitializing && !isReady -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Downloading AI model…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    isGenerating -> {
                        Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Generating a quick recap…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (onCancel != null) {
                                TextButton(onClick = onCancel) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }

                    summary != null -> {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    else -> {
                        Text(
                            text = "Need a quick refresher before you open this chapter?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = onGenerateSummary) {
                            Text("Generate summary")
                        }
                    }
                }
            }
        }
    }
}
