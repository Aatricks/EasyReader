package io.aatricks.easyreader.util

import java.util.zip.ZipFile

object ZipUtils {
    fun readZipEntrySafely(zip: ZipFile, name: String, limit: Long = 10 * 1024 * 1024): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > limit) throw Exception("File too large")

        zip.getInputStream(entry).use { input ->
            val baos = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                total += count
                if (total > limit) throw Exception("File too large")
                baos.write(buffer, 0, count)
            }
            return baos.toByteArray()
        }
    }
}
