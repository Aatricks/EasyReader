package io.aatricks.easyreader.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateCheckResult {
    object NoNewVersion : UpdateCheckResult
    data class NewVersion(
        val versionName: String,
        val changelog: String,
        val downloadUrl: String,
        val fileSize: Long
    ) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface DownloadStatus {
    object Idle : DownloadStatus
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadStatus
    data class Success(val apkFile: File) : DownloadStatus
    data class Error(val message: String) : DownloadStatus
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long
)

@Serializable
private data class GithubComparison(
    val status: String
)

internal enum class CommitComparisonStatus {
    AHEAD,
    BEHIND,
    DIVERGED,
    IDENTICAL
}

internal object UpdateEligibility {
    private const val VERSION_DELIMITER = "."

    fun shouldOfferUpdate(
        releaseVersion: String,
        currentVersion: String,
        commitComparisonStatus: CommitComparisonStatus?
    ): Boolean {
        if (!isVersionNewer(releaseVersion, currentVersion)) return false
        return commitComparisonStatus != CommitComparisonStatus.AHEAD &&
            commitComparisonStatus != CommitComparisonStatus.IDENTICAL
    }

    fun isVersionNewer(tag: String, current: String): Boolean {
        val cleanTag = tag.replace(Regex("(?i)^v"), "").substringBefore("-").trim()
        val cleanCurrent = current.replace(Regex("(?i)^v"), "").substringBefore("-").trim()
        val tagParts = cleanTag.split(VERSION_DELIMITER)
        val currentParts = cleanCurrent.split(VERSION_DELIMITER)

        for (index in 0 until maxOf(tagParts.size, currentParts.size)) {
            val tagValue = tagParts.getOrNull(index)?.toIntOrNull() ?: 0
            val currentValue = currentParts.getOrNull(index)?.toIntOrNull() ?: 0
            if (tagValue != currentValue) return tagValue > currentValue
        }
        return false
    }
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val BUFFER_SIZE = 8192
        private const val GITHUB_API_URL = "https://api.github.com/repos/Aatricks/EasyReader/releases/latest"
        private const val GITHUB_COMPARE_API_URL = "https://api.github.com/repos/Aatricks/EasyReader/compare"
        private const val GITHUB_API_ACCEPT_HEADER = "application/vnd.github.v3+json"
        private val COMMIT_SHA_REGEX = Regex("[0-9a-fA-F]{40}")
        
        // Flavor names
        private const val FLAVOR_AI = "ai"
        private const val FLAVOR_STANDARD = "standard"
        
        // AI engine class name for flavor detection
        private const val AI_ENGINE_CLASS = "io.aatricks.easyreader.data.repository.summary.LlmEdgeSummaryEngine"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", GITHUB_API_ACCEPT_HEADER)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("HTTP error: ${response.code}")
                }
                val bodyString = response.body.string()
                val release = json.decodeFromString<GithubRelease>(bodyString)
                
                val currentVersion = getAppVersionName()
                if (!UpdateEligibility.isVersionNewer(release.tagName, currentVersion)) {
                    return@withContext UpdateCheckResult.NoNewVersion
                }
                val comparisonStatus = compareInstalledCommitToRelease(release.tagName)
                if (!UpdateEligibility.shouldOfferUpdate(release.tagName, currentVersion, comparisonStatus)) {
                    return@withContext UpdateCheckResult.NoNewVersion
                }

                val selectedAsset = selectApkAsset(release)
                if (selectedAsset != null) {
                    UpdateCheckResult.NewVersion(
                        versionName = release.tagName.replace(Regex("(?i)^v"), ""),
                        changelog = release.body.orEmpty(),
                        downloadUrl = selectedAsset.browserDownloadUrl,
                        fileSize = selectedAsset.size
                    )
                } else {
                    UpdateCheckResult.NoNewVersion
                }
            }
        } catch (e: java.io.IOException) {
            UpdateCheckResult.Error(e.message ?: "Network error")
        } catch (e: kotlinx.serialization.SerializationException) {
            UpdateCheckResult.Error(e.message ?: "Serialization error")
        } catch (e: IllegalArgumentException) {
            UpdateCheckResult.Error(e.message ?: "Invalid arguments")
        }
    }

    private fun compareInstalledCommitToRelease(tagName: String): CommitComparisonStatus? {
        val commitSha = BuildConfig.GIT_COMMIT_SHA
        if (!COMMIT_SHA_REGEX.matches(commitSha)) return null
        val request = Request.Builder()
            .url("$GITHUB_COMPARE_API_URL/$tagName...$commitSha")
            .header("Accept", GITHUB_API_ACCEPT_HEADER)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val comparison = json.decodeFromString<GithubComparison>(response.body.string())
                    runCatching {
                        CommitComparisonStatus.valueOf(comparison.status.uppercase())
                    }.getOrNull()
                } else {
                    null
                }
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun selectApkAsset(release: GithubRelease): GithubAsset? {
        val flavor = getAppFlavor()
        val apkAssets = release.assets.filter { 
            it.name.endsWith(".apk", ignoreCase = true) && 
            it.name.contains(flavor, ignoreCase = true) 
        }
        
        val selectedAsset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            apkAssets.find { it.name.contains(abi, ignoreCase = true) }
        }
        
        return selectedAsset ?: apkAssets.firstOrNull() ?: release.assets.find {
            it.name.endsWith(".apk", ignoreCase = true)
        }
    }

    fun downloadUpdate(
        url: String,
        versionName: String,
        expectedSize: Long
    ): Flow<DownloadStatus> = flow {
        val apkFile = File(context.cacheDir, "update_${versionName.replace(".", "_")}.apk")
        if (apkFile.exists() && apkFile.length() == expectedSize) {
            emit(DownloadStatus.Progress(expectedSize, expectedSize))
            emit(DownloadStatus.Success(apkFile))
            return@flow
        }

        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("update_") && it.name.endsWith(".apk")
        }?.forEach { runCatching { it.delete() } }

        emit(DownloadStatus.Progress(0, expectedSize))
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadStatus.Error("HTTP error: ${response.code}"))
                    return@flow
                }
                val body = response.body
                copyStreamToFile(body, apkFile, expectedSize) { status ->
                    emit(status)
                }
                emit(DownloadStatus.Success(apkFile))
            }
        } catch (e: java.io.IOException) {
            emit(DownloadStatus.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun copyStreamToFile(
        body: okhttp3.ResponseBody,
        apkFile: File,
        expectedSize: Long,
        emitProgress: suspend (DownloadStatus) -> Unit
    ) {
        val totalBytes = if (body.contentLength() > 0) body.contentLength() else expectedSize
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        var totalRead = 0L
        body.byteStream().use { inputStream ->
            apkFile.outputStream().use { outputStream ->
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    emitProgress(DownloadStatus.Progress(totalRead, totalBytes))
                }
            }
        }
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return
        
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        context.startActivity(intent)
    }



    fun isDebugBuild(): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }



    fun getAppVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            android.util.Log.w("AppUpdateManager", "Package name not found", e)
            "0.0.0"
        }
    }

    private fun getAppFlavor(): String {
        val isAi = try {
            Class.forName(AI_ENGINE_CLASS)
            true
        } catch (e: ClassNotFoundException) {
            android.util.Log.d("AppUpdateManager", "AI engine class not found, defaulting to standard", e)
            false
        }
        return if (isAi) FLAVOR_AI else FLAVOR_STANDARD
    }

}
