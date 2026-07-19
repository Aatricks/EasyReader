package io.aatricks.easyreader.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("InjectDispatcher")
class SettingsBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = SettingsBackup(
                schemaVersion = BACKUP_SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                appVersionName = readAppVersionName(),
                reader = ReaderSettingsPayload(
                    fontSize = preferencesManager.fontSize,
                    lineHeight = preferencesManager.lineHeight,
                    fontFamily = preferencesManager.fontFamily,
                    margins = preferencesManager.margins,
                    verticalMargins = preferencesManager.verticalMargins,
                    paragraphSpacing = preferencesManager.paragraphSpacing,
                    readerTheme = preferencesManager.readerTheme,
                    accentTheme = preferencesManager.accentTheme,
                    brightness = preferencesManager.brightness
                ),
                scrollFinishedSeries = preferencesManager.scrollFinishedSeries.toList(),
                scrollUnlockedMilestones = preferencesManager.scrollUnlockedMilestones,
                scrollHistorySeeded = preferencesManager.scrollHistorySeeded
            )
            val text = json.encodeToString(payload)
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open output stream")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.e(TAG, "Settings export failed", it) }
    }

    suspend fun importFrom(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw IOException("Could not open input stream")

            val payload = json.decodeFromString<SettingsBackup>(text)
            if (payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
                throw IOException(
                    "Backup schema ${payload.schemaVersion} is newer than supported " +
                        "(${BACKUP_SCHEMA_VERSION}). Update the app and try again."
                )
            }
            val r = payload.reader
            preferencesManager.batchUpdateReaderSettings(
                fontSize = r.fontSize,
                lineHeight = r.lineHeight,
                fontFamily = r.fontFamily,
                margins = r.margins,
                verticalMargins = r.verticalMargins,
                paragraphSpacing = r.paragraphSpacing,
                brightness = r.brightness,
                readerTheme = r.readerTheme,
                accentTheme = r.accentTheme
            )

            val currentFinished = preferencesManager.scrollFinishedSeries
            val newFinished = currentFinished + payload.scrollFinishedSeries
            if (newFinished.size > currentFinished.size) {
                preferencesManager.scrollFinishedSeries = newFinished
            }

            val currentUnlocked = preferencesManager.scrollUnlockedMilestones
            val mergedUnlocked = currentUnlocked.toMutableMap()
            var milestonesChanged = false
            for ((id, timeMs) in payload.scrollUnlockedMilestones) {
                val existing = mergedUnlocked[id]
                if (existing == null || timeMs < existing) {
                    mergedUnlocked[id] = timeMs
                    milestonesChanged = true
                }
            }
            if (milestonesChanged) {
                preferencesManager.scrollUnlockedMilestones = mergedUnlocked
            }

            if (payload.scrollHistorySeeded) {
                preferencesManager.scrollHistorySeeded = true
            }
        }.onFailure { Log.e(TAG, "Settings import failed", it) }
    }

    private fun readAppVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        private const val TAG = "SettingsBackupManager"
    }
}
