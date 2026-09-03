package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
    state: ChapterSummaryState,
    onGenerateSummary: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onEnableAi: (() -> Unit)? = null,
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
                    !state.aiSupportedInBuild -> AiUnavailableNotice()
                    !state.aiOptedIn -> AiOptInPrompt(onEnableAi)
                    state.isInitializing && !state.isReady ->
                        SummaryProgress("Downloading AI model…", onCancel = null)

                    state.isGenerating -> SummaryProgress("Generating a quick recap…", onCancel)
                    state.error != null -> SummaryError(state.error, onGenerateSummary)
                    state.summary != null -> Text(
                        text = state.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    else -> SummaryPrompt(onGenerateSummary)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AiUnavailableNotice() {
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

@Composable
private fun ColumnScope.AiOptInPrompt(onEnableAi: (() -> Unit)?) {
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

@Composable
private fun SummaryProgress(label: String, onCancel: (() -> Unit)?) {
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
                text = label,
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

@Composable
private fun ColumnScope.SummaryError(error: String, onRetry: () -> Unit) {
    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    FilledTonalButton(onClick = onRetry) {
        Text("Retry")
    }
}

@Composable
private fun ColumnScope.SummaryPrompt(onGenerateSummary: () -> Unit) {
    Text(
        text = "Need a quick refresher before you open this chapter?",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FilledTonalButton(onClick = onGenerateSummary) {
        Text("Generate summary")
    }
}

/** The slice of `SummaryViewModel.SummaryUiState` this panel renders, scoped to one chapter. */
data class ChapterSummaryState(
    val summary: String? = null,
    val error: String? = null,
    val isGenerating: Boolean = false,
    val isInitializing: Boolean = false,
    val aiSupportedInBuild: Boolean = true,
    val aiOptedIn: Boolean = true,
    val isReady: Boolean = aiSupportedInBuild && aiOptedIn
)
