package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_EPUB_IMAGE_BYTES = 50L * 1024L * 1024L
private const val MAX_EXTRACTED_IMAGE_CACHE_BYTES = 64L * 1024L * 1024L
private const val MAX_IMAGE_EXTENSION_LENGTH = 8

internal class EpubImageDiskCache(private val directory: File) {
    private val mutex = Mutex()
    private var trackedBytes: Long? = null

    suspend fun get(epubFile: File, imageHref: String): File? = mutex.withLock {
        val target = imageTarget(epubFile, imageHref)
        if (target.exists() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return@withLock target
        }
        ZipFile(epubFile).use { zip ->
            val entry = findImageEntry(zip, imageHref)
            entry?.let { extractEntry(zip, it, target) }
        }
    }

    fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
        trackedBytes = 0L
    }

    fun onExternalChange(isCleared: Boolean) {
        trackedBytes = if (isCleared) 0L else null
    }

    private fun findImageEntry(zip: ZipFile, imageHref: String): ZipEntry? =
        zip.getEntry(imageHref) ?: zip.entries().asSequence().firstOrNull { candidate ->
            val name = candidate.name.replace("\\", "/").removePrefix("/")
            name == imageHref || name.endsWith("/$imageHref")
        }

    private fun extractEntry(zip: ZipFile, entry: ZipEntry, target: File): File? {
        validateImageSize(entry.size)
        directory.mkdirs()
        return if (target.exists() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            target
        } else {
            writeEntry(zip, entry, target)
        }
    }

    private fun imageTarget(epubFile: File, imageHref: String): File {
        val identity = "${epubFile.canonicalPath}:${epubFile.length()}:${epubFile.lastModified()}|$imageHref"
        val extension = imageHref.substringAfterLast('.', "img").take(MAX_IMAGE_EXTENSION_LENGTH)
        return File(directory, "${CacheKeyUtils.keyFor(identity)}.$extension")
    }

    private fun writeEntry(zip: ZipFile, entry: ZipEntry, target: File): File? {
        val bytesBefore = trackedBytes ?: FileSizeUtils.calculateDirectorySize(directory)
        val temp = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            copyEntry(zip, entry, temp)
            if (temp.length() > 0L) {
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                updateTrackedBytes(bytesBefore + target.length())
            }
        } finally {
            temp.delete()
        }
        return target.takeIf { it.exists() && it.length() > 0L }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, temp: File) {
        zip.getInputStream(entry).use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                var count = input.read(buffer)
                while (count >= 0) {
                    total += count
                    validateImageSize(total)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
        }
    }

    private fun validateImageSize(bytes: Long) {
        if (bytes > MAX_EPUB_IMAGE_BYTES) throw IOException("EPUB image exceeds extraction limit")
    }

    private fun updateTrackedBytes(estimatedBytes: Long) {
        trackedBytes = if (estimatedBytes > MAX_EXTRACTED_IMAGE_CACHE_BYTES) {
            FileSizeUtils.trimDirectoryToSize(directory, MAX_EXTRACTED_IMAGE_CACHE_BYTES)
        } else {
            estimatedBytes
        }
    }
}
