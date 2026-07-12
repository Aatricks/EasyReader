package io.aatricks.easyreader.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.updater.AppUpdateManager
import io.aatricks.easyreader.updater.DownloadStatus
import io.aatricks.easyreader.updater.UpdateCheckResult
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context
) : BaseViewModel<UpdateViewModel.UpdateUiState>(
    UpdateUiState(
        currentVersion = appUpdateManager.getAppVersionName(),
        automaticUpdateChecksEnabled = preferencesManager.automaticUpdateChecksEnabled
    )
) {

    private companion object {
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000L
    }

    data class UpdateUiState(
        val currentVersion: String = "",
        val automaticUpdateChecksEnabled: Boolean = true,
        val isChecking: Boolean = false,
        val updateAvailable: UpdateCheckResult.NewVersion? = null,
        val downloadStatus: DownloadStatus = DownloadStatus.Idle,
        val error: String? = null
    )

    fun checkForUpdates() {
        updateState { it.copy(isChecking = true, error = null, updateAvailable = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            when (val result = appUpdateManager.checkForUpdates()) {
                is UpdateCheckResult.NewVersion -> {
                    preferencesManager.lastAppUpdateCheckTime = now
                    updateState { it.copy(isChecking = false, updateAvailable = result) }
                }
                is UpdateCheckResult.NoNewVersion -> {
                    preferencesManager.lastAppUpdateCheckTime = now
                    updateState { it.copy(isChecking = false, updateAvailable = null) }
                }
                is UpdateCheckResult.Error -> {
                    updateState { it.copy(isChecking = false, error = result.message) }
                }
            }
        }
    }

    fun checkForUpdatesIfNeeded() {
        if (appUpdateManager.isDebugBuild()) return
        if (!preferencesManager.automaticUpdateChecksEnabled) return
        val lastCheck = preferencesManager.lastAppUpdateCheckTime
        val now = System.currentTimeMillis()
        if (now - lastCheck >= CHECK_INTERVAL_MS) {
            viewModelScope.launch {
                when (val result = appUpdateManager.checkForUpdates()) {
                    is UpdateCheckResult.NewVersion -> {
                        preferencesManager.lastAppUpdateCheckTime = now
                        updateState { it.copy(updateAvailable = result) }
                    }
                    is UpdateCheckResult.NoNewVersion -> {
                        preferencesManager.lastAppUpdateCheckTime = now
                    }
                    is UpdateCheckResult.Error -> {
                        // Let it retry on next launch if we had an error
                    }
                }
            }
        }
    }

    fun setAutomaticUpdateChecksEnabled(enabled: Boolean) {
        preferencesManager.automaticUpdateChecksEnabled = enabled
        updateState { it.copy(automaticUpdateChecksEnabled = enabled) }
    }

    fun startDownload(
        downloadUrl: String,
        versionName: String,
        expectedSize: Long
    ) {
        viewModelScope.launch {
            appUpdateManager.downloadUpdate(downloadUrl, versionName, expectedSize).collect { status ->
                updateState { it.copy(downloadStatus = status) }
            }
        }
    }

    fun installApk(apkFile: java.io.File) {
        appUpdateManager.installApk(apkFile)
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    fun clearUpdateState() {
        updateState {
            it.copy(
                updateAvailable = null,
                downloadStatus = DownloadStatus.Idle,
                error = null
            )
        }
    }
}
