package io.aatricks.easyreader.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    updateState: UpdateViewModel.UpdateUiState
) {
    val context = LocalContext.current
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showPermissionWarningDialog by remember { mutableStateOf(false) }
    var showDownloadProgressDialog by remember { mutableStateOf(false) }

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
            Toast.makeText(context, "Download failed: ${status.message}", Toast.LENGTH_LONG).show()
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
        title = { Text("Update Emaki") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                Text(
                    text = "Version $currentVersion  →  ${update.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (update.changelog.isNotBlank()) {
                    Text(
                        text = "What's new",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    changelogContent(update.changelog)
                }
                Text(
                    text = "Download · ${FormatBytesUtils.formatBytes(update.fileSize)}",
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
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = { 
                onDismiss()
                updateViewModel.clearUpdateState()
            }) {
                Text("Not now")
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
        "Downloading update ($percent%)…"
    } else {
        "Downloading update…"
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Downloading Update") },
        text = { Text(progressText) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                updateViewModel.clearUpdateState()
            }) {
                Text("Cancel")
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
        title = { Text("Install Update") },
        text = {
            Text(
                "The update was downloaded successfully. " +
                    "Tap Install to start the installation."
            )
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                if (updateViewModel.canInstallPackages()) {
                    updateViewModel.installApk(successStatus.apkFile)
                } else {
                    onShowPermissionWarning()
                }
            }) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = { updateViewModel.clearUpdateState() }) {
                Text("Cancel")
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
        title = { Text("Permission Required") },
        text = {
            Text(
                "To install updates, Emaki needs permission to install apps from " +
                    "unknown sources. You will be taken to system settings to enable this."
            )
        },
        confirmButton = {
            FilledTonalButton(onClick = {
                onDismiss()
                val intent = updateViewModel.requestInstallPermissionIntent()
                if (intent != null) {
                    runCatching { context.startActivity(intent) }
                }
            }) {
                Text("Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                updateViewModel.clearUpdateState()
            }) {
                Text("Cancel")
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
