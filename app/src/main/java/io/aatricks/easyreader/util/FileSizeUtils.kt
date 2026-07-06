package io.aatricks.easyreader.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object FileSizeUtils {
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
