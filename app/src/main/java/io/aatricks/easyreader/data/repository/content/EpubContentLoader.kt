package io.aatricks.easyreader.data.repository.content

import android.content.Context
import android.net.Uri
import android.util.LruCache
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.di.EpubCacheDir
import io.aatricks.easyreader.di.EpubDownloadsDir
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubContentLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    @EpubCacheDir private val epubCacheDir: File,
    @EpubDownloadsDir private val epubDownloadsDir: File
) {
    private val epubBookCache = object : LruCache<String, EpubBook>(5) {}

    suspend fun loadEpubContent(filePath: String, chapterHref: String? = null): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(filePath) ?: throw Exception("Failed to load EPUB")
            val href = chapterHref ?: book.spine.firstOrNull() ?: throw Exception("No chapters")
            val chapter = loadEpubChapter(filePath, book, href)
            ContentResult.Success(chapter.content, chapter.title ?: book.metadata.title, "$filePath#$href")
        }.getOrElse { e ->
            ContentResult.Error("EPUB Error: ${e.message}")
        }
    }

    suspend fun getEpubBook(path: String): EpubBook? = withContext(Dispatchers.IO) {
        runCatching {
            epubBookCache.get(path) ?: parseEpubFile(path).also { epubBookCache.put(path, it) }
        }.getOrNull()
    }

    suspend fun loadEpubChapterFull(path: String, href: String): EpubChapter? = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(path) ?: throw Exception("Failed to load EPUB")
            loadEpubChapter(path, book, href)
        }.getOrNull()
    }

    suspend fun prefetchEpub(path: String, tier: StorageTier = StorageTier.CACHE): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = resolveEpubFile(path, tier)
            if (tier == StorageTier.DOWNLOADS) {
                promoteEpubToDownloads(path)
            }
            file.exists() && getEpubBook(path) != null
        }.getOrDefault(false)
    }

    suspend fun getEpubImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val parts = url.split("#img:", limit = 2).takeIf { it.size == 2 } ?: return@withContext null
            val epubPath = parts[0]
            val imgHref = parts[1].replace("\\", "/").removePrefix("/")

            val fileToRead = resolveEpubFile(epubPath)
            if (!fileToRead.exists()) return@withContext null

            try {
                ZipFile(fileToRead).use { zip ->
                    val entry = zip.getEntry(imgHref)
                        ?: zip.entries().asSequence().firstOrNull {
                            val name = it.name.replace("\\", "/").removePrefix("/")
                            name == imgHref || name.endsWith("/$imgHref")
                        }

                    entry?.let {
                        ZipUtils.readZipEntrySafely(zip, it.name, 50 * 1024 * 1024)
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }.getOrNull()
    }

    fun clearCache(url: String) {
        epubBookCache.remove(url)
        prefetchedImageDirVariants(url).forEach { it.deleteRecursively() }
        cachedEpubFileVariants(url).forEach { it.delete() }
    }

    fun clearDownload(url: String) {
        epubBookCache.remove(url)
        primaryEpubFile(url, StorageTier.DOWNLOADS).delete()
        File(epubDownloadsDir, CacheKeyUtils.keyFor(url)).deleteRecursively()
    }

    fun clearAllCache() {
        epubCacheDir.deleteRecursively()
        epubBookCache.evictAll()
        epubCacheDir.mkdirs()
    }

    fun clearAllDownloads() {
        epubDownloadsDir.deleteRecursively()
        epubBookCache.evictAll()
        epubDownloadsDir.mkdirs()
    }

    fun getCacheSize(): Long {
        return FileSizeUtils.calculateDirectorySize(epubCacheDir)
    }

    fun getDownloadsSize(): Long {
        return FileSizeUtils.calculateDirectorySize(epubDownloadsDir)
    }

    fun isCached(path: String): Boolean {
        return if (path.startsWith("content://")) {
            findExistingCachedEpubFile(path) != null
        } else {
            File(path).exists()
        }
    }

    fun isDownloaded(path: String): Boolean {
        return if (path.startsWith("content://")) {
            primaryEpubFile(path, StorageTier.DOWNLOADS).exists()
        } else {
            File(path).exists()
        }
    }

    private fun parseEpubFile(filePath: String): EpubBook {
        val file = resolveEpubFile(filePath)

        ZipFile(file).use { zip ->
            val cont = ZipUtils.readZipEntrySafely(zip, "META-INF/container.xml") ?: throw Exception("No container.xml")
            val opfPath = Jsoup.parse(String(cont), "", org.jsoup.parser.Parser.xmlParser()).select("rootfile").attr("full-path")
            val opfContent = ZipUtils.readZipEntrySafely(zip, opfPath) ?: throw Exception("No OPF")
            val opfDoc = Jsoup.parse(String(opfContent), "", org.jsoup.parser.Parser.xmlParser())

            val meta = EpubMetadata(
                title = opfDoc.select("metadata dc|title, title").first()?.text() ?: "Unknown",
                author = opfDoc.select("dc|creator").first()?.text()
            )

            val base = opfPath.substringBeforeLast("/", "")
            val manifest = mutableMapOf<String, String>()
            opfDoc.select("manifest item").forEach {
                val id = it.attr("id")
                if (id.isNotBlank()) {
                    val href = it.attr("href")
                    manifest[id] = if (base.isNotBlank()) "$base/$href" else href
                }
            }

            val spine = mutableListOf<String>()
            opfDoc.select("spine itemref").forEach { manifest[it.attr("idref")]?.let { h -> spine.add(h) } }

            val ncxPath = manifest.values.firstOrNull { it.endsWith("toc.ncx") }
            val ncxBytes = if (ncxPath != null) ZipUtils.readZipEntrySafely(zip, ncxPath) else null

            val toc = parseTocNcx(ncxBytes, manifest, base) ?: emptyList()
            return EpubBook(meta, toc, spine, manifest)
        }
    }

    private fun parseTocNcx(ncxBytes: ByteArray?, manifest: Map<String, String>, base: String): List<EpubTocItem>? {
        if (ncxBytes == null) return null
        val doc = Jsoup.parse(String(ncxBytes), "", org.jsoup.parser.Parser.xmlParser())

        fun parsePoint(e: org.jsoup.nodes.Element): EpubTocItem {
            val src = e.select("content").attr("src").let { if (it.startsWith("/")) it.drop(1) else it }
            val resolvedSrc = (if (base.isNotBlank() && !src.contains("/")) "$base/$src" else src).substringBefore("#")
            return EpubTocItem(
                id = e.attr("id"),
                title = e.select("navLabel text").first()?.text() ?: "Chapter",
                href = resolvedSrc,
                children = e.select("> navPoint").map { parsePoint(it) }
            )
        }

        return doc.select("navMap > navPoint").map { parsePoint(it) }
    }

    private fun loadEpubChapter(filePath: String, book: EpubBook, href: String): EpubChapter {
        val file = resolveEpubFile(filePath)

        var bytes: ByteArray? = null
        try {
            ZipFile(file).use { zip ->
                var entry = zip.getEntry(href)
                if (entry == null) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.name == href || e.name.endsWith(href)) {
                            entry = e
                            break
                        }
                    }
                }

                if (entry != null) {
                     bytes = ZipUtils.readZipEntrySafely(zip, entry.name)
                }
            }
        } catch (e: Exception) {
            if (filePath.startsWith("content://")) file.delete()
            throw e
        }

        val doc = Jsoup.parse(String(bytes ?: throw Exception("No chapter bytes")))
        val els = mutableListOf<ContentElement>()

        fun traverse(element: org.jsoup.nodes.Element) {
            val tagName = element.tagName().lowercase()
            when {
                tagName == "img" || tagName == "image" -> {
                    val src = if (tagName == "img") {
                        element.attr("src")
                    } else {
                        element.attr("xlink:href").ifEmpty { element.attr("href") }
                    }
                    if (src.isNotBlank()) {
                        els.add(ContentElement.Image("$filePath#img:${resolveEpubPath(href, src)}", element.attr("alt")))
                    }
                }
                tagName in setOf("p", "h1", "h2", "h3", "h4", "li") -> {
                    val text = element.text().trim()
                    if (text.length > 1) {
                        els.add(ContentElement.Text(text))
                    }
                    element.select("img, image").forEach { img ->
                        val iTagName = img.tagName().lowercase()
                        val src = if (iTagName == "img") {
                            img.attr("src")
                        } else {
                            img.attr("xlink:href").ifEmpty { img.attr("href") }
                        }
                        if (src.isNotBlank()) {
                            els.add(ContentElement.Image("$filePath#img:${resolveEpubPath(href, src)}", img.attr("alt")))
                        }
                    }
                }
                else -> {
                    element.children().forEach { traverse(it) }
                    val ownText = element.ownText().trim()
                    if (ownText.length > 1 && element.children().none { it.tagName().lowercase() in setOf("p", "div", "h1", "h2", "h3", "h4", "li") }) {
                        els.add(ContentElement.Text(ownText))
                    }
                }
            }
        }

        doc.body()?.let { traverse(it) }

        return EpubChapter(
            href = href,
            title = book.findTocItemByHref(href)?.title,
            content = els,
            nextHref = book.getNextHref(href),
            previousHref = book.getPreviousHref(href)
        )
    }

    private fun resolveEpubPath(base: String, rel: String): String {
        if (rel.startsWith("/")) return rel.drop(1)
        val parent = base.substringBeforeLast("/", "")
        val combined = if (parent.isNotBlank()) "$parent/$rel" else rel

        val parts = combined.split("/")
        val result = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "." -> {}
                ".." -> if (result.isNotEmpty()) result.removeAt(result.size - 1)
                else -> if (part.isNotBlank()) result.add(part)
            }
        }
        return result.joinToString("/")
    }

    private fun resolveEpubFile(path: String, writeTier: StorageTier = StorageTier.CACHE): File {
        return if (path.startsWith("content://")) {
            findExistingCachedEpubFile(path)?.let { return it }

            val finalFile = primaryEpubFile(path, writeTier)
            if (!finalFile.exists()) {
                finalFile.parentFile?.mkdirs()
                val tmpFile = File(finalFile.parentFile, "${CacheKeyUtils.keyFor(path)}.${UUID.randomUUID()}.tmp")
                try {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Failed to open content URI")

                    if (!tmpFile.renameTo(finalFile) && !finalFile.exists()) {
                         throw Exception("Failed to cache EPUB")
                    }
                } finally {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            }
            finalFile
        } else {
            File(path).also { if (!it.exists()) throw Exception("File not found") }
        }
    }

    private fun promoteEpubToDownloads(path: String): File? {
        if (!path.startsWith("content://")) return null
        val target = primaryEpubFile(path, StorageTier.DOWNLOADS)
        if (target.exists()) return target
        val src = primaryEpubFile(path, StorageTier.CACHE).takeIf(File::exists)
            ?: legacyCachedEpubFile(path).takeIf(File::exists)
            ?: return null
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) return target
        return runCatching {
            src.copyTo(target, overwrite = true)
            src.delete()
            target
        }.getOrNull()
    }

    private fun primaryEpubFile(path: String, tier: StorageTier): File {
        val dir = when (tier) {
            StorageTier.DOWNLOADS -> epubDownloadsDir
            StorageTier.CACHE -> epubCacheDir
        }
        return File(dir, "${CacheKeyUtils.keyFor(path)}.epub")
    }

    private fun legacyCachedEpubFile(path: String): File =
        File(epubCacheDir, "${path.hashCode()}.epub")

    private fun findExistingCachedEpubFile(path: String): File? =
        cachedEpubFileVariants(path).firstOrNull(File::exists)

    private fun cachedEpubFileVariants(path: String): List<File> = listOf(
        primaryEpubFile(path, StorageTier.DOWNLOADS),
        primaryEpubFile(path, StorageTier.CACHE),
        legacyCachedEpubFile(path)
    ).distinctBy(File::getAbsolutePath)

    private fun prefetchedImageDirVariants(path: String): List<File> {
        val key = CacheKeyUtils.keyFor(path)
        val legacyKey = path.hashCode().toString()
        return listOf(
            File(epubDownloadsDir, key),
            File(epubCacheDir, key),
            File(epubCacheDir, legacyKey)
        ).distinctBy(File::getAbsolutePath)
    }
}
