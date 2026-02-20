package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import android.graphics.BitmapFactory
import io.aatricks.novelscraper.data.model.*
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.annotation.VisibleForTesting

/**
 * Repository for content loading and processing (Web, PDF, HTML, EPUB)
 */
@Singleton
class ContentRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val htmlParser: HtmlParser,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "ContentRepository"
        private val DIMENSION_SEMAPHORE = Semaphore(10)
        private const val MAX_CONCURRENT_DOWNLOADS = 5
        private val PAGE_NUMBER_REGEX = Regex("^\\d+$")
        private val PARAGRAPH_SPLIT_REGEX = Regex("\\n\\s*\\n")

        // Optimization: Define Regex patterns as constants
        private val CHAPTER_URL_PATTERNS = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cacheDir: File get() = File(context.cacheDir, "html_cache").apply { if (!exists()) mkdirs() }
    private val mediaCacheDir: File get() = File(context.cacheDir, "media_cache").apply { if (!exists()) mkdirs() }
    private val epubCacheDir: File get() = File(context.cacheDir, "epub_cache").apply { if (!exists()) mkdirs() }

    private val epubBookCache = mutableMapOf<String, EpubBook>()

    sealed class ContentResult {
        data class Success(
            val elements: List<ContentElement>,
            val title: String? = null,
            val url: String,
            val textCount: Int? = null,
            val imageCount: Int? = null
        ) : ContentResult()
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
        if (url.contains("mangabat") || url.contains("manganato")) {
            "https://manganato.com/"
        } else {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        }
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
        val finalElements = processChapterElements(elements, url)

        backgroundCacheImages(finalElements, url)
        
        ContentResult.Success(
            elements = finalElements,
            title = document.title().takeIf { it.isNotBlank() },
            url = url
        )
    }

    @VisibleForTesting
    internal fun backgroundCacheImages(elements: List<ContentElement>, pageUrl: String): Unit {
        repositoryScope.launch {
            val imageChannel = Channel<String>(Channel.UNLIMITED)

            repeat(MAX_CONCURRENT_DOWNLOADS) {
                launch {
                    for (url in imageChannel) {
                        downloadAndCacheImage(url, pageUrl)
                    }
                }
            }

            elements.forEach { element ->
                when (element) {
                    is ContentElement.Image -> imageChannel.trySend(element.url)
                    is ContentElement.ImageGroup -> element.images.forEach { img ->
                        imageChannel.trySend(img.url)
                    }
                    else -> {}
                }
            }
            imageChannel.close()
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

            okHttpClient.newCall(request).execute().use {
                if (!it.isSuccessful) return@runCatching null
                it.body?.let { body ->
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

        okHttpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            return it.body?.string() ?: throw Exception("Empty body")
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

    private suspend fun processChapterElements(elements: List<ContentElement>, url: String): List<ContentElement> {
        val imageElements = elements.flatMap { element ->
            when (element) {
                is ContentElement.Image -> listOf(element)
                is ContentElement.ImageGroup -> element.images
                else -> emptyList()
            }
        }

        if (imageElements.isEmpty()) return elements

        val imagesWithDims = withContext(Dispatchers.IO) {
            imageElements.map { img ->
                async {
                    DIMENSION_SEMAPHORE.withPermit {
                        fetchImageDimensions(img.url, url)?.let { (w, h) ->
                            img.copy(width = w, height = h)
                        } ?: img
                    }
                }
            }.awaitAll()
        }

        val dimMap = imageElements.zip(imagesWithDims).toMap()

        val expandedElements = mutableListOf<ContentElement>()
        for (element in elements) {
            when (element) {
                is ContentElement.Image -> {
                    val img = dimMap[element] ?: element
                    // Conservative splitting: only for very wide images that are likely double-page spreads
                    if (img.width > img.height * 1.5 && img.width > 1600 && img.height > 0) {
                        // For manga-like sources, we might want RIGHT then LEFT, but for general web 
                        // content or Manhwa/Manhua, LEFT then RIGHT is more appropriate.
                        // Given the name "EasyReader", we'll stick to a default.
                        val isManga = url.contains("manga", ignoreCase = true) && !url.contains("manhwa", ignoreCase = true)
                        if (isManga) {
                            expandedElements.add(img.copy(side = ContentElement.Image.Side.RIGHT))
                            expandedElements.add(img.copy(side = ContentElement.Image.Side.LEFT))
                        } else {
                            expandedElements.add(img.copy(side = ContentElement.Image.Side.LEFT))
                            expandedElements.add(img.copy(side = ContentElement.Image.Side.RIGHT))
                        }
                    } else {
                        expandedElements.add(img)
                    }
                }
                is ContentElement.ImageGroup -> {
                    val updatedImages = element.images.map { dimMap[it] ?: it }
                    expandedElements.add(element.copy(images = updatedImages))
                }
                else -> expandedElements.add(element)
            }
        }

        return groupSimilarElements(expandedElements)
    }

    private fun groupSimilarElements(elements: List<ContentElement>): List<ContentElement> {
        if (elements.isEmpty()) return emptyList()
        val processed = mutableListOf<ContentElement>()
        
        for (element in elements) {
            if (processed.isEmpty()) {
                processed.add(element)
                continue
            }
            
            val last = processed.last()
            if (element is ContentElement.Image) {
                when {
                    shouldGroupWithLastImage(last, element) -> {
                        processed[processed.size - 1] = ContentElement.ImageGroup(listOf(last as ContentElement.Image, element))
                    }
                    shouldGroupWithLastGroup(last, element) -> {
                        val group = last as ContentElement.ImageGroup
                        processed[processed.size - 1] = ContentElement.ImageGroup(group.images + element)
                    }
                    else -> processed.add(element)
                }
            } else {
                processed.add(element)
            }
        }
        return processed
    }

    private fun shouldGroupWithLastImage(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.Image || last.width <= 0 || current.width <= 0 || last.width != current.width) {
            return false
        }
        // Don't group split images
        if (last.side != ContentElement.Image.Side.FULL || current.side != ContentElement.Image.Side.FULL) {
            return false
        }
        val lastRatio = last.height.toFloat() / last.width
        val currentRatio = current.height.toFloat() / current.width
        return (currentRatio < 0.8f || lastRatio < 0.8f) && lastRatio + currentRatio < 2.1f
    }

    private fun shouldGroupWithLastGroup(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.ImageGroup) return false
        // Don't group split images into groups
        if (current.side != ContentElement.Image.Side.FULL) {
            return false
        }
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

            okHttpClient.newCall(req).execute().use {
                if (it.isSuccessful) {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(it.body?.byteStream(), null, opt)
                    if (opt.outWidth > 0) Pair(opt.outWidth, opt.outHeight) else null
                } else null
            }
        }.getOrNull()
    }

    private inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int
    ) : AbstractList<ContentElement>(), java.io.Closeable {
        private var pdfDocument: PdfDocument? = null
        private var inputStream: java.io.InputStream? = null
        private val lock = Any()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            val text = loadPageText(index + 1)
            return ContentElement.Text(text)
        }

        private fun getOrOpenDocument(): PdfDocument? {
            synchronized(lock) {
                if (pdfDocument != null && !pdfDocument!!.isClosed) {
                    return pdfDocument
                }

                val doc = runCatching {
                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        val stream = context.contentResolver.openInputStream(uri) ?: return null
                        inputStream = stream
                        PdfDocument(PdfReader(stream))
                    } else {
                        val file = File(filePath)
                        if (!file.exists()) return null
                        PdfDocument(PdfReader(file))
                    }
                }.getOrNull()

                pdfDocument = doc
                return doc
            }
        }

        private fun loadPageText(pageNum: Int): String {
            return runCatching {
                val doc = getOrOpenDocument() ?: return ""
                if (pageNum > doc.numberOfPages) return ""

                val rawText = PdfTextExtractor.getTextFromPage(doc.getPage(pageNum))

                rawText.lines()
                    .filterNot { it.trim().matches(PAGE_NUMBER_REGEX) }
                    .joinToString("\n")
                    .split(PARAGRAPH_SPLIT_REGEX)
                    .map { it.trim() }
                    .filter { it.length > 20 }
                    .joinToString("\n\n")
            }.getOrDefault("")
        }

        override fun close() {
            synchronized(lock) {
                try {
                    pdfDocument?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                pdfDocument = null

                try {
                    inputStream?.close()
                } catch (e: Exception) {
                    // Ignore stream close errors
                }
                inputStream = null
            }
        }
    }

    private suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val pageCount = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use {
                    PdfDocument(PdfReader(it)).use { doc -> doc.numberOfPages }
                } ?: throw Exception("PDF not found")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("PDF not found")
                PdfDocument(PdfReader(file)).use { doc -> doc.numberOfPages }
            }

            if (pageCount == 0) throw Exception("No text in PDF")

            val title = if (filePath.startsWith("content://")) {
                Uri.parse(filePath).lastPathSegment ?: "PDF"
            } else {
                File(filePath).nameWithoutExtension
            }

            ContentResult.Success(
                elements = PdfLazyList(filePath, pageCount),
                title = title,
                url = filePath,
                textCount = pageCount,
                imageCount = 0
            )
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
        val file = resolveEpubFile(filePath)
        
        ZipFile(file).use {
            val cont = readZipEntrySafely(it, "META-INF/container.xml") ?: throw Exception("No container.xml")
            val opfPath = Jsoup.parse(String(cont), "", org.jsoup.parser.Parser.xmlParser()).select("rootfile").attr("full-path")
            val opfContent = readZipEntrySafely(it, opfPath) ?: throw Exception("No OPF")
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
            val ncxBytes = if (ncxPath != null) readZipEntrySafely(it, ncxPath) else null

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
            ZipFile(file).use {
                var entry = it.getEntry(href)
                if (entry == null) {
                    val entries = it.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.name == href || e.name.endsWith(href)) {
                            entry = e
                            break
                        }
                    }
                }

                if (entry != null) {
                     bytes = readZipEntrySafely(it, entry.name)
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
                    // Also check for images nested inside this block element
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
                    // If an element like <div> contains direct text, handle it
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

    suspend fun incrementChapterUrl(url: String): String? = adjustChapterUrl(url, 1)
    suspend fun decrementChapterUrl(url: String): String? = adjustChapterUrl(url, -1)
    
    private fun adjustChapterUrl(url: String, delta: Int): String? {
        for (p in CHAPTER_URL_PATTERNS) {
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
            
            val file = resolveEpubFile(path)
            ZipFile(file).use {
                val entries = it.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && isImageFile(e.name)) {
                        val outFile = File(dir, e.name.replace("/", "_"))
                        it.getInputStream(e).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
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
                        readZipEntrySafely(zip, it.name, 50 * 1024 * 1024)
                    }
                }
            } catch (e: Exception) {
                // Don't delete cached file just because of read error
                throw e
            }
        }.getOrNull()
    }

    suspend fun clearCache(url: String): Unit = withContext(Dispatchers.IO) {
        if (url.contains("epub")) {
            epubBookCache.remove(url)
            File(epubCacheDir, url.hashCode().toString()).deleteRecursively()
            File(epubCacheDir, "${url.hashCode()}.epub").delete()
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
            if (!dir.exists()) return@sumOf 0L
            val path = dir.toPath()
            var size = 0L
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        size += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (e: Exception) {
                // Ignore
            }
            size
        }
    }

    private fun resolveEpubFile(path: String): File {
        return if (path.startsWith("content://")) {
            val finalFile = File(epubCacheDir, "${path.hashCode()}.epub")
            if (!finalFile.exists()) {
                // Use a unique temp file to avoid race conditions during concurrent downloads
                val tmpFile = File.createTempFile("epub_", ".tmp", epubCacheDir)
                try {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Failed to open content URI")

                    // Atomic rename.
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

    private fun readZipEntrySafely(zip: ZipFile, name: String, limit: Long = 10 * 1024 * 1024): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > limit) throw Exception("File too large")

        zip.getInputStream(entry).use {
            val baos = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            var count: Int
            while (it.read(buffer).also { count = it } != -1) {
                total += count
                if (total > limit) throw Exception("File too large")
                baos.write(buffer, 0, count)
            }
            return baos.toByteArray()
        }
    }
}
