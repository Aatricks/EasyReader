package io.aatricks.easyreader.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.backup.LibraryBackupManager
import io.aatricks.easyreader.data.backup.SettingsBackupManager
import io.aatricks.easyreader.work.LibraryUpdateWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsBackupManager: SettingsBackupManager,
    private val libraryBackupManager: LibraryBackupManager
) : ViewModel() {

    sealed interface OpStatus {
        data object Idle : OpStatus
        data object InProgress : OpStatus
        data class Success(val message: String) : OpStatus
        data class Error(val message: String) : OpStatus
    }

    private val _status = MutableStateFlow<OpStatus>(OpStatus.Idle)
    val status: StateFlow<OpStatus> = _status.asStateFlow()

    fun exportSettings(uri: Uri) {
        runOp {
            settingsBackupManager.exportTo(uri)
                .fold(
                    onSuccess = { OpStatus.Success("Settings exported") },
                    onFailure = { OpStatus.Error(it.userMessage("Failed to export settings")) }
                )
        }
    }

    fun importSettings(uri: Uri) {
        runOp {
            settingsBackupManager.importFrom(uri)
                .fold(
                    onSuccess = { OpStatus.Success("Settings restored") },
                    onFailure = { OpStatus.Error(it.userMessage("Failed to import settings")) }
                )
        }
    }

    fun exportLibrary(uri: Uri) {
        runOp {
            libraryBackupManager.exportTo(uri)
                .fold(
                    onSuccess = { count -> OpStatus.Success("Exported $count items") },
                    onFailure = { OpStatus.Error(it.userMessage("Failed to export library")) }
                )
        }
    }

    fun importLibrary(uri: Uri) {
        runOp {
            libraryBackupManager.importFrom(uri)
                .fold(
                    onSuccess = { summary ->
                        // Imported items keep the totalChapters/hasUpdates copied from the
                        // backup, and their old lastRead/dateAdded would normally exclude them
                        // from the periodic update check. Kick off a one-off forced refresh so
                        // freshly restored finished series surface their NEW pills.
                        if (summary.imported > 0) {
                            LibraryUpdateWorker.runOnce(appContext)
                        }
                        val parts = buildList {
                            add("Imported ${summary.imported}")
                            if (summary.duplicates > 0) {
                                val suffix = if (summary.duplicates == 1) "" else "s"
                                add("${summary.duplicates} duplicate$suffix")
                            }
                            if (summary.invalid > 0) add("${summary.invalid} invalid")
                        }
                        OpStatus.Success(parts.joinToString(" · "))
                    },
                    onFailure = { OpStatus.Error(it.userMessage("Failed to import library")) }
                )
        }
    }

    fun ackStatus() {
        _status.value = OpStatus.Idle
    }

    private fun runOp(block: suspend () -> OpStatus) {
        _status.value = OpStatus.InProgress
        viewModelScope.launch {
            _status.value = runCatching { block() }.getOrElse {
                OpStatus.Error(it.userMessage("Operation failed"))
            }
        }
    }

    private fun Throwable.userMessage(fallback: String): String =
        message?.takeIf { it.isNotBlank() } ?: fallback
}
