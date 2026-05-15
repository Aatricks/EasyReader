package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.di.MediaCacheDir
import io.aatricks.easyreader.di.MediaDownloadsDir
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class StorageTier { DOWNLOADS, CACHE }

@Singleton
class ImageCache @Inject constructor(
    @MediaCacheDir private val mediaCacheDir: File,
    @MediaDownloadsDir private val mediaDownloadsDir: File
) {
    fun getCachedMediaFile(url: String): File =
        findExistingCachedMediaFile(url) ?: primaryCachedMediaFile(url)

    fun getRootDir(): File = mediaCacheDir

    fun getCacheSize(): Long = FileSizeUtils.calculateDirectorySize(mediaCacheDir)

    fun getDownloadsSize(): Long = FileSizeUtils.calculateDirectorySize(mediaDownloadsDir)

    fun trimToSize(maxBytes: Long): Long = FileSizeUtils.trimDirectoryToSize(mediaCacheDir, maxBytes)

    fun findExistingCachedMediaFile(url: String): File? =
        candidateFiles(url).firstOrNull(File::exists)

    fun destinationFile(url: String, tier: StorageTier): File {
        val key = CacheKeyUtils.keyFor(url)
        return when (tier) {
            StorageTier.DOWNLOADS -> File(mediaDownloadsDir, key)
            StorageTier.CACHE -> File(mediaCacheDir, key)
        }
    }

    fun isDownloaded(url: String): Boolean =
        File(mediaDownloadsDir, CacheKeyUtils.keyFor(url)).exists()

    fun deleteCachedMediaFiles(url: String) {
        candidateFiles(url).forEach { it.delete() }
    }

    fun deleteDownloadedMediaFile(url: String) {
        File(mediaDownloadsDir, CacheKeyUtils.keyFor(url)).delete()
    }

    fun clearAll() {
        mediaCacheDir.deleteRecursively()
        mediaCacheDir.mkdirs()
    }

    fun clearAllDownloads() {
        mediaDownloadsDir.deleteRecursively()
        mediaDownloadsDir.mkdirs()
    }

    fun promoteToDownloads(url: String): File? {
        val key = CacheKeyUtils.keyFor(url)
        val target = File(mediaDownloadsDir, key)
        if (target.exists()) return target
        val source = File(mediaCacheDir, key).takeIf { it.exists() }
            ?: File(mediaCacheDir, url.hashCode().toString()).takeIf { it.exists() }
            ?: return null
        target.parentFile?.mkdirs()
        return if (source.renameTo(target)) target else {
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }
    }

    private fun primaryCachedMediaFile(url: String): File =
        File(mediaCacheDir, CacheKeyUtils.keyFor(url))

    private fun candidateFiles(url: String): List<File> {
        val key = CacheKeyUtils.keyFor(url)
        val legacyKey = url.hashCode().toString()
        return listOf(
            File(mediaDownloadsDir, key),
            File(mediaCacheDir, key),
            File(mediaCacheDir, legacyKey)
        ).distinctBy(File::getAbsolutePath)
    }
}
