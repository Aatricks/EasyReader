package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import io.aatricks.easyreader.di.MediaCacheDir

@Singleton
class ImageCache @Inject constructor(
    @MediaCacheDir private val mediaCacheDir: File
) {
    fun getCachedMediaFile(url: String): File = findExistingCachedMediaFile(url) ?: primaryCachedMediaFile(url)

    fun getRootDir(): File = mediaCacheDir

    fun getCacheSize(): Long = FileSizeUtils.calculateDirectorySize(mediaCacheDir)

    fun trimToSize(maxBytes: Long): Long = FileSizeUtils.trimDirectoryToSize(mediaCacheDir, maxBytes)

    fun findExistingCachedMediaFile(url: String): File? =
        cacheFileVariants(primaryCachedMediaFile(url), legacyCachedMediaFile(url))
            .firstOrNull(File::exists)

    fun deleteCachedMediaFiles(url: String) {
        cacheFileVariants(primaryCachedMediaFile(url), legacyCachedMediaFile(url))
            .forEach { it.delete() }
    }

    fun clearAll() {
        mediaCacheDir.deleteRecursively()
        mediaCacheDir.mkdirs()
    }

    private fun primaryCachedMediaFile(url: String): File =
        File(mediaCacheDir, CacheKeyUtils.keyFor(url))

    private fun legacyCachedMediaFile(url: String): File =
        File(mediaCacheDir, url.hashCode().toString())

    private fun cacheFileVariants(primary: File, legacy: File): List<File> =
        listOf(primary, legacy).distinctBy(File::getAbsolutePath)
}
