package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import io.aatricks.easyreader.R
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.UpdateViewModel
import io.aatricks.easyreader.updater.DownloadStatus
import io.aatricks.easyreader.updater.UpdateCheckResult
import kotlinx.coroutines.delay

private const val PERCENT_MULTIPLIER = 100
private const val BYTES_IN_KB = 1024
private const val DEFERRED_STARTUP_DELAY_MS = 2000L

@Composable
fun appUpdateHandler(
    updateViewModel: UpdateViewModel,
    updateState: UpdateViewModel.UpdateUiState,
    snackbarHostState: SnackbarHostState
) {
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showPermissionWarningDialog by remember { mutableStateOf(false) }
    var showDownloadProgressDialog by remember { mutableStateOf(false) }
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        delay(DEFERRED_STARTUP_DELAY_MS)
        updateViewModel.checkForUpdatesIfNeeded()
    }

    LaunchedEffect(updateState.updateAvailable) {
        if (updateState.updateAvailable != null) {
            showUpdateDialog = true
        }
    }

    LaunchedEffect(updateState.downloadStatus) {
        val status = updateState.downloadStatus
        if (status is DownloadStatus.Success) {
            showDownloadProgressDialog = false
            showInstallDialog = true
        } else if (status is DownloadStatus.Error) {
            showDownloadProgressDialog = false
            val failure = resources.getString(R.string.update_download_failed, status.message)
            snackbarHostState.showSnackbar(failure, duration = SnackbarDuration.Long)
        } else if (status is DownloadStatus.Progress) {
            showDownloadProgressDialog = true
        }
    }

    if (showUpdateDialog) {
        val update = updateState.updateAvailable
        if (update != null) {
            updateDialog(update, updateState.currentVersion, updateViewModel) {
                showUpdateDialog = false
            }
        }
    }

    if (showDownloadProgressDialog) {
        val status = updateState.downloadStatus as? DownloadStatus.Progress
        if (status != null) {
            downloadProgressDialog(status, updateViewModel) {
                showDownloadProgressDialog = false
            }
        }
    }

    if (showInstallDialog) {
        val successStatus = updateState.downloadStatus as? DownloadStatus.Success
        if (successStatus != null) {
            installDialog(successStatus, updateViewModel) {
                showInstallDialog = false
                showPermissionWarningDialog = true
            }
        }
    }

    if (showPermissionWarningDialog) {
        permissionWarningDialog(updateViewModel) {
            showPermissionWarningDialog = false
        }
    }
}

@Composable
private fun updateDialog(
    update: UpdateCheckResult.NewVersion,
    currentVersion: String,
    updateViewModel: UpdateViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { 
            onDismiss()
            updateViewModel.clearUpdateState()
        },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                Text(
                    text = stringResource(
                        R.string.update_version_change,
                        currentVersion,
                        update.versionName
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (update.changelog.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.update_whats_new),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    changelogContent(update.changelog)
                }
                Text(
                    text = stringResource(
                        R.string.update_download_size,
                        FormatBytesUtils.formatBytes(update.fileSize)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onDismiss()
                updateViewModel.startDownload(update.downloadUrl, update.versionName, update.fileSize)
            }) {
                Text(stringResource(R.string.update_action_update))
            }
        },
        dismissButton = {
            TextButton(onClick = { 
                onDismiss()
                updateViewModel.clearUpdateState()
            }) {
                Text(stringResource(R.string.update_action_not_now))
            }
        }
    )
}

@Composable
private fun changelogContent(changelog: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(
            modifier = Modifier
                .padding(EasyReaderSpacing.md)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = cleanChangelog(changelog),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun downloadProgressDialog(
    status: DownloadStatus.Progress,
    updateViewModel: UpdateViewModel,
    onDismiss: () -> Unit
) {
    val percent = if (status.totalBytes > 0) {
        val bytes = status.bytesDownloaded
        val total = status.totalBytes
        (bytes * PERCENT_MULTIPLIER / total).toInt()
    } else {
        -1
    }
    val progressText = if (percent >= 0) {
        stringResource(R.string.settings_status_downloading_update_percent, percent)
    } else {
        stringResource(R.string.settings_status_downloading_update)
    }
    val cancel = {
        onDismiss()
        updateViewModel.cancelDownload()
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = { Text(progressText) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = cancel) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun installDialog(
    successStatus: DownloadStatus.Success,
    updateViewModel: UpdateViewModel,
    onShowPermissionWarning: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_install_title)) },
        text = {
            Text(stringResource(R.string.update_install_body))
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                if (updateViewModel.canInstallPackages()) {
                    updateViewModel.installApk(successStatus.apkFile)
                } else {
                    onShowPermissionWarning()
                }
            }) {
                Text(stringResource(R.string.update_action_install))
            }
        },
        dismissButton = {
            TextButton(onClick = { updateViewModel.cancelDownload() }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun permissionWarningDialog(
    updateViewModel: UpdateViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_permission_title)) },
        text = {
            Text(stringResource(R.string.update_permission_body))
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onDismiss()
                val intent = updateViewModel.requestInstallPermissionIntent()
                if (intent != null) {
                    runCatching { context.startActivity(intent) }
                }
            }) {
                Text(stringResource(R.string.update_action_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                updateViewModel.clearUpdateState()
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private object FormatBytesUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= BYTES_IN_KB && unit < units.lastIndex) {
            value /= BYTES_IN_KB
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}"
        else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}

private fun cleanChangelog(raw: String): String {
    return raw.lines()
        .map { it.trim() }
        .filter { line ->
            line.isNotEmpty() && 
            !line.contains("Full Changelog") && 
            !line.contains("compare/V")
        }
        .map { line ->
            var cleaned = line
            cleaned = cleaned.replace(Regex("https://github.com/[^\\s]+/pull/\\d+"), "")
            cleaned = cleaned.replace(Regex("by @\\w+"), "")
            cleaned = cleaned.replace(Regex("\\s+in\\s*$"), "")
            cleaned = cleaned.replace(Regex("\\s+by\\s*$"), "")
            cleaned = cleaned.trim()
            cleaned = cleaned.replace("**", "")
            if (cleaned.startsWith("## ")) {
                cleaned.removePrefix("## ").trim() + ":"
            } else if (cleaned.startsWith("# ")) {
                cleaned.removePrefix("# ").trim() + ":"
            } else {
                cleaned
            }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
