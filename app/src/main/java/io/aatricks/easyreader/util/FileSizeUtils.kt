package io.aatricks.easyreader.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object FileSizeUtils {
    // `.tmp` files are skipped by the trim below because a live download owns one, so a
    // process death mid-download stranded them forever. Nothing this old is still being
    // written to.
    private const val STALE_TEMP_FILE_AGE_MS = 60L * 60L * 1000L

    fun deleteStaleTempFiles(dir: File, nowMs: Long = System.currentTimeMillis()) {
        if (!dir.exists()) return
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .filter { nowMs - it.lastModified() > STALE_TEMP_FILE_AGE_MS }
            .forEach { it.delete() }
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    size += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {
        }
        return size
    }

    fun trimDirectoryToSize(dir: File, maxBytes: Long, onDelete: (File) -> Unit = {}): Long {
        if (!dir.exists()) return 0L
        deleteStaleTempFiles(dir)
        val files = dir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()

        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                onDelete(file)
            }
        }
        return total
    }
}
