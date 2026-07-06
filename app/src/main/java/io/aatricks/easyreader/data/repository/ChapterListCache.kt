package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ChapterInfo
import io.aatricks.easyreader.di.ChapterListCacheDir
import io.aatricks.easyreader.util.CacheKeyUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent cache for the (baseNovelUrl, sourceName) → chapter list mapping.
 * Lives under filesDir so it survives Android cache reclamation. Used to avoid
 * re-fetching the same chapter list every time the reader opens a chapter — that
 * call is the loudest non-content network request after the download/cache rework.
 */
@Singleton
class ChapterListCache @Inject constructor(
    @ChapterListCacheDir private val cacheDir: File,
    private val json: Json
) {
    companion object {
        const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000 // 6 hours

        // Bump when a source's chapter-list parsing changes in a way that should invalidate every
        // already-cached list. Entries written before versioning default to 0, so a bump forces a
        // one-time refetch on next open (e.g. the Novelight paging fix that recovers dropped pages).
        const val CACHE_VERSION = 1
    }

    @Serializable
    data class Entry(
        val chapters: List<ChapterInfo>,
        val fetchedAt: Long,
        val baseNovelUrl: String,
        val sourceName: String,
        val version: Int = 0
    )

    fun load(baseNovelUrl: String, sourceName: String): Entry? {
        if (baseNovelUrl.isBlank() || sourceName.isBlank()) return null
        val file = fileFor(baseNovelUrl, sourceName)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(Entry.serializer(), file.readText())
        }.getOrNull()
    }

    fun save(baseNovelUrl: String, sourceName: String, chapters: List<ChapterInfo>) {
        if (baseNovelUrl.isBlank() || sourceName.isBlank() || chapters.isEmpty()) return
        val file = fileFor(baseNovelUrl, sourceName)
        runCatching {
            file.parentFile?.mkdirs()
            val entry = Entry(
                chapters = chapters,
                fetchedAt = System.currentTimeMillis(),
                baseNovelUrl = baseNovelUrl,
                sourceName = sourceName,
                version = CACHE_VERSION
            )
            val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
            try {
                temp.writeText(json.encodeToString(Entry.serializer(), entry))
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                }
            } finally {
                temp.delete()
            }
        }
    }

    fun invalidate(baseNovelUrl: String, sourceName: String) {
        if (baseNovelUrl.isBlank() || sourceName.isBlank()) return
        fileFor(baseNovelUrl, sourceName).delete()
    }

    fun clearAll() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    fun isFresh(entry: Entry): Boolean =
        entry.version == CACHE_VERSION && (System.currentTimeMillis() - entry.fetchedAt) < FRESH_WINDOW_MS

    private fun fileFor(baseNovelUrl: String, sourceName: String): File =
        File(cacheDir, "${CacheKeyUtils.keyFor("$sourceName|$baseNovelUrl")}.json")
}
