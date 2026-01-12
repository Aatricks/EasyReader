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
        runCatching {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> loadWebContent(url)
                isLocalFile(url) -> handleLocalFile(url)
                url.lowercase().endsWith(".pdf") -> loadPdfContent(url)
                url.lowercase().endsWith(".epub") -> loadEpubContent(url)
                url.lowercase().run { endsWith(".html") || endsWith(".htm") } -> loadHtmlFile(url)
                else -> ContentResult.Error("Unsupported file type")
            }
        }.getOrElse { e ->
            ContentResult.Error("Failed to load content: ${e.message}", e as? Exception)
        }
    }

    private fun isLocalFile(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://") || url.contains("/storage/")

    private suspend fun handleLocalFile(url: String): ContentResult {
        val uri = Uri.parse(url)
        val mime = context.contentResolver.getType(uri) ?: return loadFileByExtension(url)
        
        return when {
            mime.contains("pdf", ignoreCase = true) -> loadPdfContent(url)
            mime.contains("epub", ignoreCase = true) || mime.contains("application/epub+zip", ignoreCase = true) -> loadEpubContent(url)
            mime.contains("html", ignoreCase = true) || mime.contains("text", ignoreCase = true) -> loadHtmlFile(url)
            else -> ContentResult.Error("Unsupported MIME type: $mime")
        }
    }

    private suspend fun loadFileByExtension(url: String): ContentResult =
        when {
            url.endsWith(".pdf", ignoreCase = true) -> loadPdfContent(url)
            url.endsWith(".epub", ignoreCase = true) -> loadEpubContent(url)
            url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> loadHtmlFile(url)
            else -> ContentResult.Error("Unsupported local file type")
        }

    private fun getReferer(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}/"
    } catch (e: Exception) {
        url
    }

    private suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        val cachedFile = getCachedFile(url)
        val document = if (cachedFile.exists()) {
            Jsoup.parse(cachedFile, "UTF-8", url)
        } else {
            val html = downloadHtml(url)
            cachedFile.writeText(html)
            Jsoup.parse(html, url)
        }

        val elements = htmlParser.parse(document, url)
        val finalElements = if (elements.any { it is ContentElement.Image }) {
            processImages(elements.filterIsInstance<ContentElement.Image>(), url)
        } else {
            elements
        }

        backgroundCacheImages(finalElements, url)
        
        ContentResult.Success(
            elements = finalElements,
            title = document.title().takeIf { it.isNotBlank() },
            url = url
        )
    }

    private fun backgroundCacheImages(elements: List<ContentElement>, pageUrl: String): Unit {
        repositoryScope.launch {
            elements.forEach { element ->
                when (element) {
                    is ContentElement.Image -> launch { downloadAndCacheImage(element.url, pageUrl) }
                    is ContentElement.ImageGroup -> element.images.forEach { img -> 
                        launch { downloadAndCacheImage(img.url, pageUrl) } 
                    }
                    else -> {}
                }
            }
        }
    }

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext null
        
        runCatching {
            val cachedFile = getCachedMediaFile(imageUrl)
            if (cachedFile.exists()) return@runCatching cachedFile

            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", getReferer(pageUrl))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.let { body ->
                    cachedFile.writeBytes(body.bytes())
                    cachedFile
                }
            }
        }.getOrNull()
    }

    fun getCachedMediaFile(url: String): File = File(mediaCacheDir, url.hashCode().toString())

    private fun downloadHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", getReferer(url))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty body")
        }
    }

    private suspend fun loadHtmlFile(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val document = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { 
                    Jsoup.parse(it.readText(), uri.toString()) 
                } ?: throw Exception("Unable to read $filePath")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("File not found")
                Jsoup.parse(file, "UTF-8")
            }
            
            ContentResult.Success(
                elements = htmlParser.parse(document, filePath),
                title = document.title().takeIf { it.isNotBlank() },
                url = filePath
            )
        }.getOrElse { e ->
            ContentResult.Error("Failed to load HTML: ${e.message}")
        }
    }

    private fun getCachedFile(url: String): File = File(cacheDir, "${url.hashCode()}.html")

    fun isCached(url: String): Boolean = getCachedFile(url).exists()

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> 
                    getEpubBook(url)?.metadata?.title
                
                url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") -> {
                    if (url.startsWith("content://")) {
                        Uri.parse(url).lastPathSegment?.substringBeforeLast(".") ?: "PDF"
                    } else {
                        File(url).nameWithoutExtension
                    }
                }
                
                url.startsWith("http") -> {
                    val cached = getCachedFile(url)
                    val doc = if (cached.exists()) {
                        Jsoup.parse(cached, "UTF-8", url)
                    } else {
                        val html = downloadHtml(url)
                        cached.writeText(html)
                        Jsoup.parse(html, url)
                    }
                    doc.title().takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }.getOrNull()
    }

    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("http") -> {
                    val html = downloadHtml(url)
                    getCachedFile(url).writeText(html)
                    val doc = Jsoup.parse(html, url)
                    
                    htmlParser.parse(doc, url)
                        .filterIsInstance<ContentElement.Image>()
                        .forEach { img ->
                            repositoryScope.launch {
                                runCatching { downloadAndCacheImage(img.url, url) }
                            }
                        }
                    true
                }
                
                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> 
                    prefetchEpub(url)
                
                url.startsWith("content://") || url.startsWith("file://") -> {
                    context.contentResolver.openInputStream(Uri.parse(url))?.close()
                    true
                }
                
                else -> File(url).exists()
            }
        }.getOrDefault(false)
    }

    private suspend fun processImages(images: List<ContentElement.Image>, url: String): List<ContentElement> {
        val imagesWithDims = withContext(Dispatchers.IO) {
            images.map { img -> 
                async { 
                    DIMENSION_SEMAPHORE.withPermit { 
                        fetchImageDimensions(img.url, url)?.let { (w, h) ->
                            img.copy(width = w, height = h)
                        } ?: img
                    } 
                } 
            }.awaitAll()
        }
        
        return groupSimilarImages(imagesWithDims)
    }

    private fun groupSimilarImages(images: List<ContentElement.Image>): List<ContentElement> {
        if (images.isEmpty()) return emptyList()
        val processed = mutableListOf<ContentElement>(images[0])
        
        for (i in 1 until images.size) {
            val current = images[i]
            val last = processed.last()
            
            when {
                shouldGroupWithLastImage(last, current) -> {
                    processed[processed.size - 1] = ContentElement.ImageGroup(listOf(last as ContentElement.Image, current))
                }
                shouldGroupWithLastGroup(last, current) -> {
                    val group = last as ContentElement.ImageGroup
                    processed[processed.size - 1] = ContentElement.ImageGroup(group.images + current)
                }
                else -> processed.add(current)
            }
        }
        return processed
    }

    private fun shouldGroupWithLastImage(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.Image || last.width <= 0 || current.width <= 0 || last.width != current.width) {
            return false
        }
        val lastRatio = last.height.toFloat() / last.width
        val currentRatio = current.height.toFloat() / current.width
        return (currentRatio < 0.8f || lastRatio < 0.8f) && lastRatio + currentRatio < 2.1f
    }

    private fun shouldGroupWithLastGroup(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.ImageGroup) return false
        val lastInGroup = last.images.last()
        if (lastInGroup.width <= 0 || current.width <= 0 || lastInGroup.width != current.width) {
            return false
        }
        val groupRatio = last.images.sumOf { it.height }.toFloat() / lastInGroup.width
        val currentRatio = current.height.toFloat() / current.width
        return currentRatio < 0.8f && groupRatio + currentRatio < 2.1f
    }

    private suspend fun fetchImageDimensions(imageUrl: String, pageUrl: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext null
        
        runCatching {
            val cached = getCachedMediaFile(imageUrl)
            if (cached.exists()) {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, opt)
                if (opt.outWidth > 0) return@runCatching Pair(opt.outWidth, opt.outHeight)
            }

            val req = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", getReferer(pageUrl))
                .addHeader("Range", "bytes=0-16383")
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(response.body?.byteStream(), null, opt)
                    if (opt.outWidth > 0) Pair(opt.outWidth, opt.outHeight) else null
                } else null
            }
        }.getOrNull()
    }

    private suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val paragraphs = mutableListOf<String>()
            val pdfDoc = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use { 
                    PdfDocument(PdfReader(it)) 
                } ?: throw Exception("PDF not found")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("PDF not found")
                PdfDocument(PdfReader(file))
            }
            
            pdfDoc.use { doc ->
                for (i in 1..doc.numberOfPages) {
                    PdfTextExtractor.getTextFromPage(doc.getPage(i)).lines()
                        .filterNot { it.trim().matches(Regex("^\\d+$")) }
                        .joinToString("\n")
                        .split(Regex("\n\\s*\\n"))
                        .map { it.trim() }
                        .filter { it.length > 20 }
                        .forEach { paragraphs.add(it) }
                }
            }
            
            if (paragraphs.isEmpty()) throw Exception("No text in PDF")
            
            val title = if (filePath.startsWith("content://")) {
                Uri.parse(filePath).lastPathSegment ?: "PDF"
            } else {
                File(filePath).nameWithoutExtension
            }
            
            ContentResult.Success(paragraphs.map { ContentElement.Text(it) }, title, filePath)
        }.getOrElse { e ->
            ContentResult.Error("PDF Error: ${e.message}")
        }
    }

    private suspend fun loadEpubContent(filePath: String, chapterHref: String? = null): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(filePath) ?: throw Exception("Failed to load EPUB")
            val href = chapterHref ?: book.spine.firstOrNull() ?: throw Exception("No chapters")
            val chapter = loadEpubChapter(filePath, book, href)
            ContentResult.Success(chapter.content, chapter.title ?: book.metadata.title, "$filePath#$href")
        }.getOrElse { e ->
            ContentResult.Error("EPUB Error: ${e.message}")
        }
    }

    private fun parseEpubFile(filePath: String): EpubBook {
        val stream = if (filePath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(filePath)) ?: throw Exception("EPUB stream error")
        } else {
            File(filePath).inputStream()
        }
        
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        
        val cont = entries["META-INF/container.xml"] ?: throw Exception("No container.xml")
        val opfPath = Jsoup.parse(String(cont), "", org.jsoup.parser.Parser.xmlParser()).select("rootfile").attr("full-path")
        val opfDoc = Jsoup.parse(String(entries[opfPath] ?: throw Exception("No OPF")), "", org.jsoup.parser.Parser.xmlParser())
        
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
        
        val toc = parseTocNcx(entries, manifest, base) ?: emptyList()
        return EpubBook(meta, toc, spine, manifest)
    }

    private fun parseTocNcx(entries: Map<String, ByteArray>, manifest: Map<String, String>, base: String): List<EpubTocItem>? {
        val ncx = manifest.values.firstOrNull { it.endsWith("toc.ncx") } ?: return null
        val doc = Jsoup.parse(String(entries[ncx] ?: return null), "", org.jsoup.parser.Parser.xmlParser())
        
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
        val stream = if (filePath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(filePath)) ?: throw Exception("EPUB stream error")
        } else {
            File(filePath).inputStream()
        }
        
        var bytes: ByteArray? = null
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == href || e.name.endsWith(href)) {
                    bytes = zip.readBytes()
                    break
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        
        val doc = Jsoup.parse(String(bytes ?: throw Exception("No chapter bytes")))
        val els = mutableListOf<ContentElement>()
        
        doc.select("body").first()?.children()?.forEach { e ->
            when (e.tagName()) {
                "p", "div", "h1", "h2", "h3", "h4", "li" -> {
                    if (e.select("img, image").isEmpty()) {
                        e.text().trim().let { if (it.length > 1) els.add(ContentElement.Text(it)) }
                    }
                }
                "img" -> els.add(ContentElement.Image("$filePath#img:${resolveEpubPath(href, e.attr("src"))}", e.attr("alt")))
            }
        }
        
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
        return if (parent.isNotBlank()) "$parent/$rel" else rel
    }

    suspend fun incrementChapterUrl(url: String): String? = adjustChapterUrl(url, 1)
    suspend fun decrementChapterUrl(url: String): String? = adjustChapterUrl(url, -1)
    
    private fun adjustChapterUrl(url: String, delta: Int): String? {
        val patterns = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(url) ?: continue
            val lastGroup = m.groupValues.last()
            val n = (lastGroup.toIntOrNull() ?: continue) + delta
            if (n < 1) return null
            
            val newNum = n.toString().padStart(lastGroup.length, '0')
            return url.replaceRange(m.range, m.value.replace(lastGroup, newNum))
        }
        return null
    }

    private suspend fun prefetchEpub(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(path) ?: throw Exception("Failed to load EPUB")
            val dir = File(epubCacheDir, path.hashCode().toString()).apply { mkdirs() }
            
            val stream = if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path)) ?: return@withContext false
            } else {
                File(path).inputStream()
            }
            
            ZipInputStream(stream).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && isImageFile(e.name)) {
                        val outFile = File(dir, e.name.replace("/", "_"))
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun isImageFile(f: String): Boolean = f.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

    suspend fun getEpubBook(path: String): EpubBook? = withContext(Dispatchers.IO) {
        runCatching {
            epubBookCache[path] ?: parseEpubFile(path).also { epubBookCache[path] = it }
        }.getOrNull()
    }

    suspend fun loadEpubChapterFull(path: String, href: String): EpubChapter? = withContext(Dispatchers.IO) {
        runCatching {
            val book = getEpubBook(path) ?: throw Exception("Failed to load EPUB")
            loadEpubChapter(path, book, href)
        }.getOrNull()
    }

    suspend fun getEpubImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val parts = url.split("#img:", limit = 2).takeIf { it.size == 2 } ?: return@withContext null
            val epubPath = parts[0]
            val imgHref = parts[1]
            
            val stream = if (epubPath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(epubPath)) ?: return@withContext null
            } else {
                File(epubPath).inputStream()
            }
            
            ZipInputStream(stream).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == imgHref || e.name.endsWith(imgHref)) return@runCatching zip.readBytes()
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            null
        }.getOrNull()
    }

    suspend fun clearCache(url: String): Unit = withContext(Dispatchers.IO) {
        if (url.contains("epub")) {
            epubBookCache.remove(url)
            File(epubCacheDir, url.hashCode().toString()).deleteRecursively()
        } else {
            getCachedFile(url).delete()
        }
    }

    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.deleteRecursively()
            mediaCacheDir.deleteRecursively()
            epubCacheDir.deleteRecursively()
            epubBookCache.clear()
            cacheDir.mkdirs()
            mediaCacheDir.mkdirs()
            epubCacheDir.mkdirs()
            true
        }.getOrDefault(false)
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        listOf(cacheDir, mediaCacheDir, epubCacheDir).sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
}