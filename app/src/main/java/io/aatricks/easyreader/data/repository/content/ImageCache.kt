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

    fun getLikelyCachedMediaFile(url: String): File? =
        candidateFiles(url).firstOrNull { it.exists() && it.length() > 0L }

    // Memoized: the reader probes this on the main thread for every image item entering
    // composition (twice per item before the memo), and a fast up/down scroll re-enters items
    // continuously — up to 3 candidate files × exists/length/mtime syscalls each time. Entries
    // are dropped whenever a write path touches the url's candidate files (see mutators below
    // and WebContentLoader.downloadAndCacheImageInternal); staleness is otherwise harmless
    // because HttpMediaCacheFetcher re-checks disk authoritatively before serving.
    fun getLikelyMediaState(url: String): String {
        // Hit path stays lookup-only (same shape as isCachedImageValid below): the size guard
        // both costs a CHM sweep and, when it trips, would wipe the live chapter's hot entries
        // on what was a plain read.
        mediaStateMemo[url]?.let { return it }
        if (mediaStateMemo.size > MAX_MEDIA_STATE_MEMO) mediaStateMemo.clear()
        return mediaStateMemo.computeIfAbsent(url) { computeLikelyMediaState(it) }
    }

    private fun computeLikelyMediaState(url: String): String {
        val file = getLikelyCachedMediaFile(url) ?: return "missing"
        return "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }

    fun invalidateMediaState(url: String) {
        mediaStateMemo.remove(url)
    }

    fun getRootDir(): File = mediaCacheDir

    fun getCacheSize(): Long = FileSizeUtils.calculateDirectorySize(mediaCacheDir)

    fun getDownloadsSize(): Long = FileSizeUtils.calculateDirectorySize(mediaDownloadsDir)

    fun trimToSize(maxBytes: Long): Long {
        // Trim runs routinely (after chapter loads, every ~30s during prefetch) and usually
        // deletes nothing — the memo must survive those calls or the memoization is defeated.
        // When files ARE deleted there is no per-url mapping back from file names, so drop the
        // whole memo, and only AFTER the walk finishes: clearing first would let a concurrent
        // probe re-memoize a file the walk is about to delete.
        var deletedAny = false
        val remaining = FileSizeUtils.trimDirectoryToSize(mediaCacheDir, maxBytes) { deletedAny = true }
        if (deletedAny) mediaStateMemo.clear()
        return remaining
    }

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

    private val mediaStateMemo = ConcurrentHashMap<String, String>()

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
        private const val MAX_MEDIA_STATE_MEMO = 4096
    }

    // Memo invalidation always FOLLOWS the disk mutation: invalidating first leaves a window
    // where a concurrent probe re-memoizes the pre-mutation state, which then sticks.
    fun deleteCachedMediaFiles(url: String) {
        candidateFiles(url).forEach { it.delete() }
        mediaStateMemo.remove(url)
    }

    fun deleteDownloadedMediaFile(url: String) {
        File(mediaDownloadsDir, CacheKeyUtils.keyFor(url)).delete()
        mediaStateMemo.remove(url)
    }

    fun clearAll() {
        mediaCacheDir.deleteRecursively()
        mediaCacheDir.mkdirs()
        mediaStateMemo.clear()
    }

    fun clearAllDownloads() {
        mediaDownloadsDir.deleteRecursively()
        mediaDownloadsDir.mkdirs()
        mediaStateMemo.clear()
    }

    fun promoteToDownloads(url: String): File? = try {
        promoteToDownloadsInternal(url)
    } finally {
        mediaStateMemo.remove(url)
    }

    private fun promoteToDownloadsInternal(url: String): File? {
        val key = CacheKeyUtils.keyFor(url)
        val target = File(mediaDownloadsDir, key)
        if (target.exists()) {
            if (target.isCachedImageValid()) return target
            target.delete()
        }
        val source = File(mediaCacheDir, key).takeIf { it.exists() }
            ?: File(mediaCacheDir, url.hashCode().toString()).takeIf { it.exists() }
            ?: return null
        if (!source.isCachedImageValid()) return null
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return target
        // Cross-filesystem promotions fall back to copy+delete. A copyTo failure
        // (disk full, permission, etc.) used to surface as an uncaught IOException
        // and crash the calling cacheImages batch — return null so the caller treats
        // this image as missing and the inspect path drives the correct demotion.
        return runCatching {
            source.copyTo(target, overwrite = true)
            if (!target.isCachedImageValid()) {
                target.delete()
                return@runCatching null
            }
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
