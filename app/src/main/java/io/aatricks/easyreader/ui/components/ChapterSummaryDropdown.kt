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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.R
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
                    text = stringResource(R.string.library_chapter_summary),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))

                when {
                    !state.aiSupportedInBuild -> AiUnavailableNotice()
                    !state.aiOptedIn -> AiOptInPrompt(onEnableAi)
                    state.isInitializing && !state.isReady ->
                        SummaryProgress(stringResource(R.string.summary_downloading_model), onCancel = null)

                    state.isGenerating -> SummaryProgress(stringResource(R.string.summary_generating), onCancel)
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
        text = stringResource(R.string.summary_unavailable_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
    Text(
        text = stringResource(R.string.summary_unavailable_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ColumnScope.AiOptInPrompt(onEnableAi: (() -> Unit)?) {
    Text(
        text = stringResource(R.string.summary_optin_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
    Text(
        text = stringResource(R.string.summary_optin_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (onEnableAi != null) {
        FilledTonalButton(onClick = onEnableAi) {
            Text(stringResource(R.string.summary_enable))
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
                Text(stringResource(R.string.common_cancel))
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
        Text(stringResource(R.string.common_retry))
    }
}

@Composable
private fun ColumnScope.SummaryPrompt(onGenerateSummary: () -> Unit) {
    Text(
        text = stringResource(R.string.summary_prompt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FilledTonalButton(onClick = onGenerateSummary) {
        Text(stringResource(R.string.summary_generate))
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
