package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import android.graphics.BitmapFactory
import io.aatricks.novelscraper.data.model.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for content loading and processing (Web, PDF, HTML, EPUB)
 */
@Singleton
class ContentRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val htmlParser: HtmlParser
) {

    companion object {
        private const val TAG = "ContentRepository"
        private val DIMENSION_SEMAPHORE = Semaphore(10)
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File get() = File(context.cacheDir, "html_cache").apply { if (!exists()) mkdirs() }
    private val mediaCacheDir: File get() = File(context.cacheDir, "media_cache").apply { if (!exists()) mkdirs() }
    private val epubCacheDir: File get() = File(context.cacheDir, "epub_cache").apply { if (!exists()) mkdirs() }

    private val epubBookCache = mutableMapOf<String, EpubBook>()

    sealed class ContentResult {
        data class Success(val elements: List<ContentElement>, val title: String? = null, val url: String) : ContentResult()
        data class Error(val message: String, val exception: Exception? = null) : ContentResult()
    }

    suspend fun loadContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) return@withContext loadWebContent(url)
            
            if (url.startsWith("content://") || url.startsWith("file://") || url.contains("/storage/")) {
                try {
                    val uri = Uri.parse(url)
                    val mime = context.contentResolver.getType(uri)
                    if (mime != null) {
                        return@withContext when {
                            mime.contains("pdf") -> loadPdfContent(url)
                            mime.contains("epub") || mime.contains("application/epub+zip") -> loadEpubContent(url)
                            mime.contains("html") || mime.contains("text") -> loadHtmlFile(url)
                            else -> ContentResult.Error("Unsupported MIME type: $mime")
                        }
                    }
                } catch (_: Exception) {}
            }

            when {
                url.endsWith(".pdf", ignoreCase = true) -> loadPdfContent(url)
                url.endsWith(".epub", ignoreCase = true) -> loadEpubContent(url)
                url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> loadHtmlFile(url)
                else -> ContentResult.Error("Unsupported file type")
            }
        } catch (e: Exception) {
            ContentResult.Error("Failed to load content: ${e.message}", e)
        }
    }

    private suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val cachedFile = getCachedFile(url)
            val document = if (cachedFile.exists()) Jsoup.parse(cachedFile, "UTF-8", url)
            else {
                val html = downloadHtml(url)
                cachedFile.writeText(html)
                Jsoup.parse(html, url)
            }

            val title = document.title().takeIf { it.isNotBlank() }
            val elements = htmlParser.parse(document, url)
            val finalElements = if (elements.any { it is ContentElement.Image }) processImages(elements.filterIsInstance<ContentElement.Image>(), url) else elements

            repositoryScope.launch {
                finalElements.forEach { element ->
                    when (element) {
                        is ContentElement.Image -> launch { downloadAndCacheImage(element.url, url) }
                        is ContentElement.ImageGroup -> element.images.forEach { img -> launch { downloadAndCacheImage(img.url, url) } }
                        else -> {}
                    }
                }
            }
            ContentResult.Success(finalElements, title, url)
        } catch (e: Exception) {
            ContentResult.Error("Failed to load web content: ${e.message}", e)
        }
    }

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            if (!imageUrl.startsWith("http")) return@withContext null
            val cachedFile = getCachedMediaFile(imageUrl)
            if (cachedFile.exists()) return@withContext cachedFile

            val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
            val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl
            val request = Request.Builder().url(imageUrl).addHeader("User-Agent", "Mozilla/5.0").addHeader("Referer", referer).build()

            okHttpClient.newCall(request).execute().use {
                if (it.isSuccessful) {
                    val body = it.body ?: return@withContext null
                    cachedFile.writeBytes(body.bytes())
                    return@withContext cachedFile
                }
            }
            null
        } catch (e: Exception) { null }
    }

    fun getCachedMediaFile(url: String): File = File(mediaCacheDir, url.hashCode().toString())

    private fun downloadHtml(url: String): String {
        val uri = try { java.net.URI(url) } catch (e: Exception) { null }
        val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else url
        val request = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").addHeader("Referer", referer).build()
        okHttpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            return it.body?.string() ?: throw Exception("Empty body")
        }
    }

    private suspend fun loadHtmlFile(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val document = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { Jsoup.parse(it.readText(), uri.toString()) }
                    ?: return@withContext ContentResult.Error("Unable to read $filePath")
            } else {
                val file = File(filePath)
                if (!file.exists()) return@withContext ContentResult.Error("File not found")
                Jsoup.parse(file, "UTF-8")
            }
            val title = document.title().takeIf { it.isNotBlank() }
            val elements = htmlParser.parse(document, filePath)
            ContentResult.Success(elements, title, filePath)
        } catch (e: Exception) { ContentResult.Error("Failed to load HTML: ${e.message}") }
    }

    private fun getCachedFile(url: String): File = File(cacheDir, url.hashCode().toString() + ".html")

    fun isCached(url: String): Boolean = getCachedFile(url).exists()

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) return@withContext getEpubBook(url)?.metadata?.title
            if (url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf")) {
                return@withContext if (url.startsWith("content://")) Uri.parse(url).lastPathSegment?.substringBeforeLast(".") ?: "PDF"
                else File(url).nameWithoutExtension
            }
            if (!url.startsWith("http")) return@withContext null
            val cached = getCachedFile(url)
            val doc = if (cached.exists()) Jsoup.parse(cached, "UTF-8", url)
            else {
                val html = downloadHtml(url)
                cached.writeText(html)
                Jsoup.parse(html, url)
            }
            doc.title().takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("http")) {
                val html = downloadHtml(url)
                getCachedFile(url).writeText(html)
                val doc = Jsoup.parse(html, url)
                htmlParser.parse(doc, url).filterIsInstance<ContentElement.Image>().forEach {
                    repositoryScope.launch { try { downloadAndCacheImage(it.url, url) } catch (_: Exception) {} }
                }
                true
            } else if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) prefetchEpub(url)
            else {
                if (url.startsWith("content://") || url.startsWith("file://")) {
                    try { Uri.parse(url).let { context.contentResolver.openInputStream(it)?.close() }; true } catch (e: Exception) { false }
                } else File(url).exists()
            }
        } catch (e: Exception) { false }
    }

    private suspend fun processImages(images: List<ContentElement.Image>, url: String): List<ContentElement> {
        val imagesWithDims = withContext(Dispatchers.IO) {
            images.map { img -> async { DIMENSION_SEMAPHORE.withPermit { val dims = fetchImageDimensions(img.url, url); if (dims != null) img.copy(width = dims.first, height = dims.second) else img } } }.awaitAll()
        }
        val processed = mutableListOf<ContentElement>()
        var i = 0
        while (i < imagesWithDims.size) {
            val current = imagesWithDims[i]
            if (processed.isNotEmpty()) {
                val last = processed.last()
                if (last is ContentElement.Image && last.width > 0 && current.width > 0 && last.width == current.width) {
                    val lastRatio = last.height.toFloat() / last.width
                    val currentRatio = current.height.toFloat() / current.width
                    if ((currentRatio < 0.8f || lastRatio < 0.8f) && lastRatio + currentRatio < 2.1f) {
                        processed.removeAt(processed.size - 1); processed.add(ContentElement.ImageGroup(listOf(last, current))); i++; continue
                    }
                } else if (last is ContentElement.ImageGroup) {
                    val lastInGroup = last.images.last()
                    if (lastInGroup.width > 0 && current.width > 0 && lastInGroup.width == current.width) {
                        val groupRatio = last.images.sumOf { it.height }.toFloat() / lastInGroup.width
                        val currentRatio = current.height.toFloat() / current.width
                        if (currentRatio < 0.8f && groupRatio + currentRatio < 2.1f) {
                            processed.removeAt(processed.size - 1); processed.add(ContentElement.ImageGroup(last.images + current)); i++; continue
                        }
                    }
                }
            }
            processed.add(current); i++
        }
        return processed
    }

    private suspend fun fetchImageDimensions(imageUrl: String, pageUrl: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            if (!imageUrl.startsWith("http")) return@withContext null
            val cached = getCachedMediaFile(imageUrl)
            if (cached.exists()) {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, opt)
                if (opt.outWidth > 0) return@withContext Pair(opt.outWidth, opt.outHeight)
            }
            val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
            val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl
            val req = Request.Builder().url(imageUrl).addHeader("User-Agent", "Mozilla/5.0").addHeader("Referer", referer).addHeader("Range", "bytes=0-16383").build()
            okHttpClient.newCall(req).execute().use {
                if (req.url.toString() == imageUrl && it.isSuccessful) {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(it.body?.byteStream(), null, opt)
                    if (opt.outWidth > 0) return@withContext Pair(opt.outWidth, opt.outHeight)
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val paragraphs = mutableListOf<String>()
            val pdfDoc = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use { PdfDocument(PdfReader(it)) } ?: return@withContext ContentResult.Error("PDF not found")
            } else {
                val file = File(filePath)
                if (!file.exists()) return@withContext ContentResult.Error("PDF not found")
                PdfDocument(PdfReader(file))
            }
            try {
                for (i in 1..pdfDoc.numberOfPages) {
                    PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i)).lines()
                        .filterNot { it.trim().matches(Regex("^\\d+$")) }
                        .joinToString("\n").split(Regex("\n\\s*\\n"))
                        .map { it.trim() }.filter { it.length > 20 }.forEach { paragraphs.add(it) }
                }
            } finally { pdfDoc.close() }
            if (paragraphs.isEmpty()) return@withContext ContentResult.Error("No text in PDF")
            val title = if (filePath.startsWith("content://")) Uri.parse(filePath).lastPathSegment ?: "PDF" else File(filePath).nameWithoutExtension
            ContentResult.Success(paragraphs.map { ContentElement.Text(it) }, title, filePath)
        } catch (e: Exception) { ContentResult.Error("PDF Error: ${e.message}") }
    }

    private suspend fun loadEpubContent(filePath: String, chapterHref: String? = null): ContentResult = withContext(Dispatchers.IO) {
        try {
            val book = epubBookCache[filePath] ?: parseEpubFile(filePath).also { epubBookCache[filePath] = it }
            val href = chapterHref ?: book.spine.firstOrNull() ?: return@withContext ContentResult.Error("No chapters")
            val chapter = loadEpubChapter(filePath, book, href)
            ContentResult.Success(chapter.content, chapter.title ?: book.metadata.title, "$filePath#$href")
        } catch (e: Exception) { ContentResult.Error("EPUB Error: ${e.message}") }
    }

    private fun parseEpubFile(filePath: String): EpubBook {
        val stream = if (filePath.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(filePath)) ?: throw Exception("EPUB stream error") else File(filePath).inputStream()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip -> var e = zip.nextEntry; while (e != null) { if (!e.isDirectory) entries[e.name] = zip.readBytes(); zip.closeEntry(); e = zip.nextEntry } }
        val cont = entries["META-INF/container.xml"] ?: throw Exception("No container.xml")
        val opfPath = Jsoup.parse(String(cont), "", org.jsoup.parser.Parser.xmlParser()).select("rootfile").attr("full-path")
        val opfDoc = Jsoup.parse(String(entries[opfPath] ?: throw Exception("No OPF")), "", org.jsoup.parser.Parser.xmlParser())
        val meta = EpubMetadata(opfDoc.select("metadata dc|title, title").first()?.text() ?: "Unknown", opfDoc.select("dc|creator").first()?.text())
        val base = opfPath.substringBeforeLast("/", "")
        val manifest = mutableMapOf<String, String>()
        opfDoc.select("manifest item").forEach { it.attr("id").let { id -> if (id.isNotBlank()) manifest[id] = if (base.isNotBlank()) "$base/${it.attr("href")}" else it.attr("href") } }
        val spine = mutableListOf<String>()
        opfDoc.select("spine itemref").forEach { manifest[it.attr("idref")]?.let { h -> spine.add(h) } }
        val toc = parseTocNcx(entries, manifest, base) ?: emptyList()
        return EpubBook(meta, toc, spine, manifest)
    }

    private fun parseTocNcx(entries: Map<String, ByteArray>, manifest: Map<String, String>, base: String): List<EpubTocItem>? {
        val ncx = manifest.values.firstOrNull { it.endsWith("toc.ncx") } ?: return null
        val doc = Jsoup.parse(String(entries[ncx] ?: return null), "", org.jsoup.parser.Parser.xmlParser())
        fun p(e: org.jsoup.nodes.Element): EpubTocItem {
            val src = e.select("content").attr("src").let { if (it.startsWith("/")) it.drop(1) else it }
            return EpubTocItem(e.attr("id"), e.select("navLabel text").first()?.text() ?: "Chapter", (if (base.isNotBlank() && !src.contains("/")) "$base/$src" else src).substringBefore("#"), children = e.select("> navPoint").map { p(it) })
        }
        return doc.select("navMap > navPoint").map { p(it) }
    }

    private fun loadEpubChapter(filePath: String, book: EpubBook, href: String, peeking: Boolean = false): EpubChapter {
        val stream = if (filePath.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(filePath)) ?: throw Exception("EPUB stream error") else File(filePath).inputStream()
        var bytes: ByteArray? = null
        ZipInputStream(stream).use { zip -> var e = zip.nextEntry; while (e != null) { if (e.name == href || e.name.endsWith(href)) { bytes = zip.readBytes(); break }; zip.closeEntry(); e = zip.nextEntry } }
        val doc = Jsoup.parse(String(bytes ?: throw Exception("No chapter bytes")))
        val els = mutableListOf<ContentElement>()
        doc.select("body").first()?.children()?.forEach { e ->
            if (e.tagName() in listOf("p", "div", "h1", "h2", "h3", "h4", "li")) {
                if (e.select("img, image").isNotEmpty()) e.children().forEach { /* nested logic simplified */ }
                else e.text().trim().let { if (it.length > 1) els.add(ContentElement.Text(it)) }
            } else if (e.tagName() == "img") els.add(ContentElement.Image("$filePath#img:${resolveEpubPath(href, e.attr("src"))}", e.attr("alt")))
        }
        return EpubChapter(href, book.findTocItemByHref(href)?.title, els, book.getNextHref(href), book.getPreviousHref(href))
    }

    private fun resolveEpubPath(base: String, rel: String) = if (rel.startsWith("/")) rel.drop(1) else base.substringBeforeLast("/", "").let { if (it.isNotBlank()) "$it/$rel" else rel }

    suspend fun incrementChapterUrl(url: String) = adjustChapterUrl(url, 1)
    suspend fun decrementChapterUrl(url: String) = adjustChapterUrl(url, -1)
    private fun adjustChapterUrl(url: String, delta: Int): String? {
        val patterns = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(url) ?: continue
            val n = (m.groupValues.last().toIntOrNull() ?: continue) + delta
            if (n < 1) return null
            return url.replaceRange(m.range, m.value.replace(m.groupValues.last(), n.toString().padStart(m.groupValues.last().length, '0')))
        }
        return null
    }

    private suspend fun prefetchEpub(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val book = epubBookCache[path] ?: parseEpubFile(path).also { epubBookCache[path] = it }
            val dir = File(epubCacheDir, path.hashCode().toString()).apply { mkdirs() }
            val stream = if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path)) ?: return@withContext false else File(path).inputStream()
            ZipInputStream(stream).use { zip -> var e = zip.nextEntry; while (e != null) { if (!e.isDirectory && isImageFile(e.name)) File(dir, e.name.replace("/", "_")).outputStream().use { zip.copyTo(it) }; zip.closeEntry(); e = zip.nextEntry } }
            true
        } catch (e: Exception) { false }
    }

    private fun isImageFile(f: String) = f.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

    suspend fun getEpubBook(path: String): EpubBook? = withContext(Dispatchers.IO) { try { epubBookCache[path] ?: parseEpubFile(path).also { epubBookCache[path] = it } } catch (e: Exception) { null } }

    suspend fun loadEpubChapterFull(path: String, href: String): EpubChapter? = withContext(Dispatchers.IO) { try { val book = epubBookCache[path] ?: parseEpubFile(path).also { epubBookCache[path] = it }; loadEpubChapter(path, book, href) } catch (e: Exception) { null } }

    suspend fun getEpubImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val parts = url.split("#img:", limit = 2).takeIf { it.size == 2 } ?: return@withContext null
            val stream = if (parts[0].startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(parts[0])) ?: return@withContext null else File(parts[0]).inputStream()
            ZipInputStream(stream).use { zip -> var e = zip.nextEntry; while (e != null) { if (e.name == parts[1] || e.name.endsWith(parts[1])) return@withContext zip.readBytes(); zip.closeEntry(); e = zip.nextEntry } }
            null
        } catch (e: Exception) { null }
    }

    suspend fun clearCache(url: String) = withContext(Dispatchers.IO) { if (url.contains("epub")) epubBookCache.remove(url).let { File(epubCacheDir, url.hashCode().toString()).deleteRecursively() } else getCachedFile(url).delete() }

    suspend fun clearAllCache() = withContext(Dispatchers.IO) { cacheDir.deleteRecursively(); mediaCacheDir.deleteRecursively(); epubCacheDir.deleteRecursively(); epubBookCache.clear(); cacheDir.mkdirs(); mediaCacheDir.mkdirs(); epubCacheDir.mkdirs(); true }

    suspend fun getCacheSize() = withContext(Dispatchers.IO) { listOf(cacheDir, mediaCacheDir, epubCacheDir).sumOf { it.listFiles()?.sumOf { f -> f.length() } ?: 0L } }
}