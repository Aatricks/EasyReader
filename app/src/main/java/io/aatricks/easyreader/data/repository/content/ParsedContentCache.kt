package io.aatricks.easyreader.data.repository.content

import android.util.Log
import io.aatricks.easyreader.data.model.ContentElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-persisted cache of post-parsed, post-enriched chapter content.
 *
 * Sidecar files sit next to the HTML file they describe (`<html>.parsed.json`) so that the
 * existing tier-aware HTML invalidation paths (`deleteCachedHtmlFiles`, `promoteHtmlToDownloads`)
 * keep them in sync without any extra bookkeeping. Validity is gated on the HTML file's
 * `lastModified()` and `length()` plus a schema `version`.
 */
@Singleton
class ParsedContentCache @Inject constructor() {

    @Serializable
    private data class Persisted(
        val version: Int,
        val mtime: Long,
        val length: Long,
        val title: String?,
        val elements: List<ContentElement>
    )

    data class Parsed(val title: String?, val elements: List<ContentElement>)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(htmlFile: File): Parsed? {
        val sidecar = sidecarFor(htmlFile)
        if (!sidecar.exists() || !htmlFile.exists()) return null
        val mtime = htmlFile.lastModified()
        val length = htmlFile.length()
        return runCatching {
            val persisted = json.decodeFromString<Persisted>(sidecar.readText())
            if (persisted.version != SCHEMA_VERSION) return@runCatching null
            if (persisted.mtime != mtime || persisted.length != length) return@runCatching null
            Parsed(persisted.title, persisted.elements)
        }.onFailure { e ->
            Log.w(TAG, "parsed cache read failed for ${sidecar.name}: ${e.message}")
            runCatching { sidecar.delete() }
        }.getOrNull()
    }

    fun save(htmlFile: File, title: String?, elements: List<ContentElement>) {
        if (!htmlFile.exists()) return
        val persisted = Persisted(
            version = SCHEMA_VERSION,
            mtime = htmlFile.lastModified(),
            length = htmlFile.length(),
            title = title,
            elements = elements
        )
        val sidecar = sidecarFor(htmlFile)
        runCatching {
            val parent = sidecar.parentFile ?: return@runCatching
            parent.mkdirs()
            val tmp = File.createTempFile("${sidecar.name}.", ".tmp", parent)
            try {
                tmp.writeText(json.encodeToString(persisted))
                if (!tmp.renameTo(sidecar) && !sidecar.exists()) {
                    throw IOException("Failed to persist parsed content for ${sidecar.name}")
                }
            } finally {
                tmp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "parsed cache write failed for ${sidecar.name}: ${e.message}")
        }
    }

    fun delete(htmlFile: File) {
        sidecarFor(htmlFile).delete()
    }

    fun moveAlongside(srcHtmlFile: File, dstHtmlFile: File) {
        val src = sidecarFor(srcHtmlFile)
        if (!src.exists()) return
        val dst = sidecarFor(dstHtmlFile)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            runCatching {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
    }

    private fun sidecarFor(htmlFile: File): File =
        File(htmlFile.parentFile, "${htmlFile.name}$SIDECAR_SUFFIX")

    companion object {
        private const val TAG = "ParsedContentCache"
        private const val SIDECAR_SUFFIX = ".parsed.json"
        private const val SCHEMA_VERSION = 1
    }
}
