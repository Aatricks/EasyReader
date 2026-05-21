package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.di.MediaCacheDir
import io.aatricks.easyreader.di.MediaDownloadsDir
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.ImageIntegrity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
        candidateFiles(url).firstOrNull { it.exists() && it.isCachedImageValid() }

    fun destinationFile(url: String, tier: StorageTier): File {
        val key = CacheKeyUtils.keyFor(url)
        return when (tier) {
            StorageTier.DOWNLOADS -> File(mediaDownloadsDir, key)
            StorageTier.CACHE -> File(mediaCacheDir, key)
        }
    }

    fun isDownloaded(url: String): Boolean {
        val file = File(mediaDownloadsDir, CacheKeyUtils.keyFor(url))
        return file.isCachedImageValid()
    }

    fun isValidImageFile(file: File): Boolean = file.isCachedImageValid()

    // Verdict cache keyed by (path, length, mtime) so re-checking a file we already validated
    // doesn't pay the disk read repeatedly. Invalidates automatically on any file mutation.
    private data class IntegrityKey(val path: String, val length: Long, val mtime: Long)
    private val integrityVerdicts = ConcurrentHashMap<IntegrityKey, Boolean>()

    private fun File.isCachedImageValid(): Boolean {
        if (!exists() || length() <= 0L) return false
        val key = IntegrityKey(absolutePath, length(), lastModified())
        integrityVerdicts[key]?.let { return it }
        val verdict = ImageIntegrity.isValidImageFile(this)
        if (integrityVerdicts.size > MAX_INTEGRITY_VERDICTS) integrityVerdicts.clear()
        integrityVerdicts[key] = verdict
        return verdict
    }

    private companion object {
        private const val MAX_INTEGRITY_VERDICTS = 4096
    }

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
        if (source.renameTo(target)) return target
        // Cross-filesystem promotions fall back to copy+delete. A copyTo failure
        // (disk full, permission, etc.) used to surface as an uncaught IOException
        // and crash the calling cacheImages batch — return null so the caller treats
        // this image as missing and the inspect path drives the correct demotion.
        return runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }.getOrNull()
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
