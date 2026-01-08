package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import android.graphics.BitmapFactory
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.util.TextUtils
import java.io.File
import java.net.URL
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
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.Element
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.select.NodeVisitor
import org.jsoup.nodes.Node

/** Repository for content operations including web scraping, HTML/PDF parsing, and caching */
class ContentRepository(private val context: Context) {

    companion object {
        private const val TAG = "ContentRepository"
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
        private val CONTINUATION_WORDS = setOf(
            "of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with"
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MULTIPLE_SPACES_REGEX = Regex(" +")
        private val DOUBLE_NEWLINE_REGEX = Regex("\n\\s*\n")
        private val CHAPTER_CLEANUP_PATTERN = Regex("(?i)^(?:chapter|chap|ch|ch\\.)[\\s:\\-\\.]*\\d+\\b.*")
        private val CHAPTER_WORD_PATTERN = Regex("(?i)chapter")
        private val DIGIT_ONLY_REGEX = Regex("^\\d+")
        
        private val MANGA_IMAGE_SELECTOR = listOf(
            ".container-chapter-reader img",
            ".vung-doc img",
            ".reader-content img",
            ".chapter-content img",
            ".chapter-img img",
            ".read-content img",
            "div.page-break img"
        ).joinToString(", ")

        private val NOVEL_CONTENT_SELECTOR = listOf(
            "article p",
            ".content p",
            ".post-content p",
            ".entry-content p",
            "#content p",
            "main p",
            "div.chapter-c p"
        ).joinToString(", ")
        
        private val DIMENSION_SEMAPHORE = Semaphore(10)
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

    private val cacheDir: File
        get() = File(context.cacheDir, "html_cache").apply { if (!exists()) mkdirs() }

    private val mediaCacheDir: File
        get() = File(context.cacheDir, "media_cache").apply { if (!exists()) mkdirs() }

    private val epubCacheDir: File
        get() = File(context.cacheDir, "epub_cache").apply { if (!exists()) mkdirs() }

    // In-memory cache for parsed EPUB books
    private val epubBookCache = mutableMapOf<String, EpubBook>()

    /** Sealed class for content operation results */
    sealed class ContentResult {
        data class Success(
                val elements: List<ContentElement>,
                val title: String? = null,
                val url: String
        ) : ContentResult()

        data class Error(val message: String, val exception: Exception? = null) : ContentResult()
    }

    /** Load content from URL (web or local file) */
    suspend fun loadContent(url: String): ContentResult =
            withContext(Dispatchers.IO) {
                try {
                    // Handle HTTP(S) web URLs
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return@withContext loadWebContent(url)
                    }

                    // Handle content:// and file:// URIs by checking MIME type where possible
                    if (url.startsWith("content://") ||
                                    url.startsWith("file://") ||
                                    url.contains("/storage/")
                    ) {
                        try {
                            val uri = android.net.Uri.parse(url)
                            val mime = context.contentResolver.getType(uri)
                            if (mime != null) {
                                return@withContext when {
                                    mime.contains("pdf") -> loadPdfContent(url)
                                    mime.contains("epub") ||
                                            mime.contains("application/epub+zip") ->
                                            loadEpubContent(url)
                                    mime.contains("html") || mime.contains("text") ->
                                            loadHtmlFile(url)
                                    else -> ContentResult.Error("Unsupported MIME type: $mime")
                                }
                            }
                        } catch (_: Exception) {
                            // fall through to extension-based detection
                        }
                    }

                    // Fallback to extension-based detection for file paths
                    when {
                        url.endsWith(".pdf", ignoreCase = true) -> loadPdfContent(url)
                        url.endsWith(".epub", ignoreCase = true) -> loadEpubContent(url)
                        url.endsWith(".html", ignoreCase = true) ||
                                url.endsWith(".htm", ignoreCase = true) -> loadHtmlFile(url)
                        else -> ContentResult.Error("Unsupported file type")
                    }
                } catch (e: Exception) {
                    ContentResult.Error("Failed to load content: ${e.message}", e)
                }
            }

    /** Load web content with caching */
    private suspend fun loadWebContent(url: String): ContentResult =
            withContext(Dispatchers.IO) {
                try {
                    // Check cache first
                    val cachedFile = getCachedFile(url)
                    val document =
                            if (cachedFile.exists()) {
                                Jsoup.parse(cachedFile, "UTF-8", url)
                            } else {
                                // Download and cache
                                val html = downloadHtml(url)
                                cachedFile.writeText(html)
                                Jsoup.parse(html, url)
                            }

                    val result = parseHtmlDocument(document, url)

                    // If successfully parsed and not from cache (or even if from cache, ensure
                    // images are cached),
                    // trigger image prefetching in background
                    if (result is ContentResult.Success) {
                        repositoryScope.launch {
                            result.elements.filterIsInstance<ContentElement.Image>().forEach { image
                                ->
                                launch {
                                    try {
                                        downloadAndCacheImage(image.url, url)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    result
                } catch (e: Exception) {
                    ContentResult.Error("Failed to load web content: ${e.message}", e)
                }
            }

    /** Download and cache an image */
    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? =
            withContext(Dispatchers.IO) {
                try {
                    if (!imageUrl.startsWith("http")) return@withContext null

                    val cachedFile = getCachedMediaFile(imageUrl)
                    if (cachedFile.exists()) return@withContext cachedFile

                    val uri =
                            try {
                                java.net.URI(pageUrl)
                            } catch (e: Exception) {
                                null
                            }
                    val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl

                    val request =
                            Request.Builder()
                                    .url(imageUrl)
                                    .addHeader(
                                            "User-Agent",
                                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                    .addHeader("Referer", referer)
                                    .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body ?: return@withContext null
                            cachedFile.writeBytes(body.bytes())
                            return@withContext cachedFile
                        }
                    }
                    null
                } catch (e: Exception) {
                    null
                }
            }

    /** Get cached media file for URL */
    fun getCachedMediaFile(url: String): File {
        val filename = url.hashCode().toString()
        return File(mediaCacheDir, filename)
    }

    /** Download HTML using OkHttp */
    private fun downloadHtml(url: String): String {
        val uri =
                try {
                    java.net.URI(url)
                } catch (e: Exception) {
                    null
                }
        val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else url

        val request =
                Request.Builder()
                        .url(url)
                        .addHeader(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                        .addHeader("Referer", referer)
                        .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    /** Load local HTML file */
    private suspend fun loadHtmlFile(filePath: String): ContentResult =
            withContext(Dispatchers.IO) {
                try {
                    val document =
                            if (filePath.startsWith("content://") || filePath.startsWith("file://")
                            ) {
                                val uri = Uri.parse(filePath)
                                context.contentResolver
                                        .openInputStream(uri)
                                        ?.bufferedReader(Charsets.UTF_8)
                                        ?.use { reader ->
                                            val html = reader.readText()
                                            Jsoup.parse(html, uri.toString())
                                        }
                                        ?: return@withContext ContentResult.Error(
                                                "Unable to read HTML content: $filePath"
                                        )
                            } else {
                                val file = File(filePath)
                                if (!file.exists()) {
                                    return@withContext ContentResult.Error(
                                            "File not found: $filePath"
                                    )
                                }

                                Jsoup.parse(file, "UTF-8")
                            }

                    parseHtmlDocument(document, filePath)
                } catch (e: Exception) {
                    ContentResult.Error("Failed to load HTML file: ${e.message}", e)
                }
            }

    /** Parse HTML document and extract content */
    private suspend fun parseHtmlDocument(document: Document, url: String): ContentResult {
        try {
            // Remove advertisements - target various ad-related classes and IDs
            document.select("""
                .ads-banner, 
                [class*="ads-banner"], 
                [class*="bats-ads"], 
                .ads-responsive, 
                .ads-chapter-bottom,
                .bats-detail-bottom-pos-1-detail-bottom-72,
                .sh-recommend,
                .cm-info,
                .next-chapter-img,
                [id*="ads-"],
                [class*="footer-ads"],
                .ads-contain,
                .banner-owner,
                .banner-ads,
                [class*="ads-contain"]
            """.trimIndent().replace("\n", "")).remove()

            // Remove credit/recommend images that often appear at the end
            document.select("img[alt*='credit'], img[alt*='recommend'], img[src*='credit'], img[src*='recommend'], img[alt*='ei0qg'], img[title*='ei0qg']").remove()

            // Extract title
            val title = document.title().takeIf { it.isNotBlank() }

            // Extract content elements (text and images)
            val contentElements = mutableListOf<ContentElement>()

            // Try common image container selectors for manga/manhwa
            var imagesFromSelectors = mutableListOf<ContentElement.Image>()
            val imageElements = document.select(MANGA_IMAGE_SELECTOR)
            var foundImages = false
            
            if (imageElements.isNotEmpty()) {
                val adDomains = listOf("yougetwhatyoupayfor.net", "bemobtrcks.com", "xpoker24.com", "coolgamesunblocked.com", "crazygamesunblocked.net", "abcya3.games", "eos.co.com")
                
                imageElements.forEach { element ->
                    // Skip images inside known ad links or tracking links
                    val parentLink = element.parents().firstOrNull { it.tagName() == "a" }
                    if (parentLink != null) {
                        val href = parentLink.attr("href")
                        if (adDomains.any { href.contains(it) } || href.contains("facebook.com") || href.contains("twitter.com")) {
                            return@forEach
                        }
                    }

                    val src =
                            element.attr("data-src")
                                    .ifEmpty { element.attr("data-original") }
                                    .ifEmpty { element.attr("src") }

                    if (src.isNotBlank()) {
                        // Filter out common ad/thumb images from MangaBat/Nato
                        if (src.contains("/thumb/") || src.contains("og-image-bat.png") || src.contains("logo") || src.contains("banner") || adDomains.any { src.contains(it) }) {
                            return@forEach
                        }

                        val absoluteUrl =
                                if (src.startsWith("http")) src
                                else {
                                    val domain =
                                            try {
                                                URL(url).let { "${it.protocol}://${it.host}" }
                                            } catch (e: Exception) {
                                                ""
                                            }
                                    if (src.startsWith("/")) {
                                        "$domain$src"
                                    } else {
                                        val base = url.substringBeforeLast("/")
                                        "$base/$src"
                                    }
                                }
                        imagesFromSelectors.add(ContentElement.Image(url = absoluteUrl))
                    }
                }
                
                // MangaBat specific: often the last image is an ad even if classes don't match
                if ((url.contains("mangabats.com") || url.contains("manganato.com")) && imagesFromSelectors.size > 5) {
                    val lastImg = imagesFromSelectors.last()
                    // If last image is from a different host than the first one, it's likely an ad
                    val firstHost = try { URL(imagesFromSelectors.first().url).host } catch (_: Exception) { "" }
                    val lastHost = try { URL(lastImg.url).host } catch (_: Exception) { "" }
                    
                    if (lastImg.url.contains("recommend") || lastImg.url.contains("banner") || lastImg.url.contains("next") || 
                        lastImg.url.contains("/thumb/") || (lastHost.isNotBlank() && firstHost != lastHost)) {
                        imagesFromSelectors.removeAt(imagesFromSelectors.size - 1)
                    }
                }

                if (imagesFromSelectors.size > 2) { 
                    foundImages = true
                }
            }

            // Extract paragraphs from various possible containers
            val paragraphs = mutableListOf<String>()
            val novelElements = document.select(NOVEL_CONTENT_SELECTOR)
            
            if (novelElements.isNotEmpty()) {
                novelElements.forEach { element ->
                    val text = extractTextPreservingLineBreaks(element)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                    }
                }
            }

            // If no specific content container found, fallback to all p tags but only if we didn't
            // find many images
            if (paragraphs.isEmpty() && imagesFromSelectors.size <= 5) {
                val elements = document.select("p")
                elements.forEach { element ->
                    val text = extractTextPreservingLineBreaks(element)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                    }
                }
            }

            // Filter paragraphs
            val filteredParagraphs =
                    paragraphs.filter { raw ->
                        val p = raw.trim()
                        if (p.isEmpty()) return@filter false
                        if (p.matches(DIGIT_ONLY_REGEX)) return@filter false
                        if (CHAPTER_CLEANUP_PATTERN.containsMatchIn(p)) return@filter false
                        if (p.length <= 80 &&
                                        p.contains(CHAPTER_WORD_PATTERN) &&
                                        p.any { it.isDigit() }
                        )
                                return@filter false
                        if (title != null) {
                            val tnorm = title.trim()
                            if (tnorm.isNotBlank() &&
                                            (p.equals(tnorm, ignoreCase = true) ||
                                                    p.startsWith(tnorm))
                            )
                                    return@filter false
                        }
                        true
                    }

            // If we have many images and little text, OR many images from a manga-specific
            // selector, it's manga/manhwa
            if (imagesFromSelectors.size > 5 || (foundImages && filteredParagraphs.size < 10)) {
                // Post-process images to group split pages by checking dimensions
                val processedElements = mutableListOf<ContentElement>()
                val imagesWithDims: List<ContentElement.Image> = withContext(Dispatchers.IO) {
                    imagesFromSelectors.map { img: ContentElement.Image ->
                        async {
                            // Limit parallelism to avoid network congestion
                            DIMENSION_SEMAPHORE.withPermit {
                                val dims = fetchImageDimensions(img.url, url)
                                if (dims != null) img.copy(width = dims.first, height = dims.second) else img
                            }
                        }
                    }.awaitAll()
                }

                var i = 0
                while (i < imagesWithDims.size) {
                    val current = imagesWithDims[i]
                    
                    if (processedElements.isNotEmpty()) {
                        val last = processedElements.last()
                        if (last is ContentElement.Image && last.width > 0 && current.width > 0 && last.width == current.width) {
                            val lastRatio = last.height.toFloat() / last.width
                            val currentRatio = current.height.toFloat() / current.width
                            
                            // Group if one is a fragment (short) and combined they are not too tall
                            val totalRatio = lastRatio + currentRatio
                            val shouldGroup = (currentRatio < 0.8f || lastRatio < 0.8f) && totalRatio < 2.1f
                                             
                            if (shouldGroup) {
                                processedElements.removeAt(processedElements.size - 1)
                                processedElements.add(ContentElement.ImageGroup(listOf(last, current)))
                                i++
                                continue
                            }
                        } else if (last is ContentElement.ImageGroup) {
                            val lastInGroup = last.images.last()
                            if (lastInGroup.width > 0 && current.width > 0 && lastInGroup.width == current.width) {
                                val groupHeight = last.images.sumOf { it.height }
                                val groupRatio = groupHeight.toFloat() / lastInGroup.width
                                val currentRatio = current.height.toFloat() / current.width
                                
                                val totalRatio = groupRatio + currentRatio
                                val shouldGroup = currentRatio < 0.8f && totalRatio < 2.1f
                                
                                if (shouldGroup) {
                                    processedElements.removeAt(processedElements.size - 1)
                                    processedElements.add(ContentElement.ImageGroup(last.images + current))
                                    i++
                                    continue
                                }
                            }
                        }
                    }
                    
                    processedElements.add(current)
                    i++
                }

                return ContentResult.Success(
                        elements = processedElements,
                        title = title,
                        url = url
                )
            }

            // Otherwise, treat as novel and merge paragraphs
            if (filteredParagraphs.isEmpty()) {
                if (foundImages) {
                    return ContentResult.Success(
                            elements = imagesFromSelectors,
                            title = title,
                            url = url
                    )
                }
                return ContentResult.Error("No content found in document")
            }

            // Merge adjacent paragraphs
            val merged = mutableListOf<String>()
            var idx = 0

            fun lastWord(s: String): String {
                val parts = s.trim().split(WHITESPACE_REGEX)
                return parts.lastOrNull() ?: ""
            }

            while (idx < filteredParagraphs.size) {
                var cur = filteredParagraphs[idx].trim()
                if (cur.isEmpty()) {
                    idx++
                    continue
                }

                if (idx + 1 < filteredParagraphs.size) {
                    val next = filteredParagraphs[idx + 1].trim()
                    if (next.isNotEmpty()) {
                        val lastChar = cur.lastOrNull()
                        val lastW = lastWord(cur).lowercase()
                        val wordCount = cur.split(WHITESPACE_REGEX).size

                        val shouldMerge =
                                (lastChar != null && !SENTENCE_ENDERS.contains(lastChar)) &&
                                        (wordCount <= 8 ||
                                                lastW in CONTINUATION_WORDS ||
                                                lastW.length <= 4) &&
                                        !(cur.contains(':') && next.contains(':'))

                        if (shouldMerge) {
                            cur = (cur + " " + next).replace(MULTIPLE_SPACES_REGEX, " ")
                            idx += 2
                            while (idx < filteredParagraphs.size) {
                                val peek = filteredParagraphs[idx].trim()
                                if (peek.isEmpty()) {
                                    idx++
                                    continue
                                }
                                val peekFirst = peek.firstOrNull()
                                if (peekFirst != null &&
                                                peekFirst.isUpperCase() &&
                                                cur.trim().lastOrNull()?.let {
                                                    SENTENCE_ENDERS.contains(it)
                                                } == true
                                )
                                        break
                                cur = (cur + " " + peek).replace(MULTIPLE_SPACES_REGEX, " ")
                                idx++
                            }
                            merged.add(cur)
                            continue
                        }
                    }
                }
                merged.add(cur)
                idx++
            }

            val joined = merged.distinct().joinToString("\n\n")
            val formatted = TextUtils.formatChapterText(joined)
            val finalParagraphs =
                    formatted
                            .split(DOUBLE_NEWLINE_REGEX)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { ContentElement.Text(it) }

            return ContentResult.Success(elements = finalParagraphs, title = title, url = url)
        } catch (e: Exception) {
            return ContentResult.Error("Failed to parse HTML: ${e.message}", e)
        }
    }

    /** Load PDF content and extract text */
    private suspend fun loadPdfContent(filePath: String): ContentResult =
            withContext(Dispatchers.IO) {
                try {
                    val paragraphs = mutableListOf<String>()

                    // Support content:// URIs as well as regular file paths
                    val pdfDocument =
                            if (filePath.startsWith("content://")) {
                                val uri = Uri.parse(filePath)
                                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                    val pdfReader = PdfReader(inputStream)
                                    PdfDocument(pdfReader)
                                }
                                        ?: return@withContext ContentResult.Error(
                                                "PDF file not found: $filePath"
                                        )
                            } else {
                                val file = File(filePath)
                                if (!file.exists()) {
                                    return@withContext ContentResult.Error(
                                            "PDF file not found: $filePath"
                                    )
                                }
                                val pdfReader = PdfReader(file)
                                PdfDocument(pdfReader)
                            }

                    try {
                        for (pageNum in 1..pdfDocument.numberOfPages) {
                            val pageText =
                                    PdfTextExtractor.getTextFromPage(pdfDocument.getPage(pageNum))

                            // Remove page numbers (lines with only numbers)
                            val cleanedText =
                                    pageText.lines()
                                            .filterNot { line ->
                                                line.trim().matches(Regex("^\\d+$"))
                                            }
                                            .joinToString("\n")

                            // Split into paragraphs
                            val pageParagraphs =
                                    cleanedText.split(Regex("\n\\s*\n")).map { it.trim() }.filter {
                                        it.isNotBlank() && it.length > 20
                                    }

                            paragraphs.addAll(pageParagraphs)
                        }
                    } finally {
                        pdfDocument.close()
                    }

                    if (paragraphs.isEmpty()) {
                        return@withContext ContentResult.Error("No text content found in PDF")
                    }

                    // Try to extract title from filename
                    val title =
                            try {
                                if (filePath.startsWith("content://")) {
                                    Uri.parse(filePath).lastPathSegment ?: "PDF Document"
                                } else {
                                    File(filePath).nameWithoutExtension
                                }
                            } catch (e: Exception) {
                                "PDF Document"
                            }

                    ContentResult.Success(
                            elements = paragraphs.map { ContentElement.Text(it) },
                            title = title,
                            url = filePath
                    )
                } catch (e: Exception) {
                    ContentResult.Error("Failed to load PDF: ${e.message}", e)
                }
            }

    /** Load EPUB content and parse structure */
    private suspend fun loadEpubContent(
            filePath: String,
            chapterHref: String? = null
    ): ContentResult =
            withContext(Dispatchers.IO) {
                try {
                    // Parse EPUB if not already cached
                    val epubBook =
                            epubBookCache[filePath]
                                    ?: parseEpubFile(filePath).also { epubBookCache[filePath] = it }

                    // If chapterHref is provided, load that specific chapter
                    val href =
                            chapterHref
                                    ?: epubBook.spine.firstOrNull()
                                            ?: return@withContext ContentResult.Error(
                                            "No chapters found in EPUB"
                                    )

                    val chapter = loadEpubChapter(filePath, epubBook, href)

                    ContentResult.Success(
                            elements = chapter.content,
                            title = chapter.title ?: epubBook.metadata.title,
                            url = "$filePath#$href"
                    )
                } catch (e: Exception) {
                    ContentResult.Error("Failed to load EPUB: ${e.message}", e)
                }
            }

    /** Parse EPUB file structure */
    private fun parseEpubFile(filePath: String): EpubBook {
        val inputStream =
                if (filePath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(filePath))
                            ?: throw Exception("Cannot open EPUB file")
                } else {
                    File(filePath).inputStream()
                }

        val zipEntries = mutableMapOf<String, ByteArray>()

        // Extract all files from EPUB (ZIP)
        ZipInputStream(inputStream).use { zipStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zipStream.readBytes()
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        // Find and parse container.xml to locate OPF file
        val containerXml =
                zipEntries["META-INF/container.xml"]
                        ?: throw Exception("container.xml not found in EPUB")

        val containerDoc =
                Jsoup.parse(String(containerXml), "", org.jsoup.parser.Parser.xmlParser())
        val opfPath =
                containerDoc.select("rootfile").attr("full-path")
                        ?: throw Exception("OPF file path not found in container.xml")

        // Parse OPF file
        val opfContent = zipEntries[opfPath] ?: throw Exception("OPF file not found: $opfPath")
        val opfDoc = Jsoup.parse(String(opfContent), "", org.jsoup.parser.Parser.xmlParser())

        // Extract metadata
        val metadata = parseEpubMetadata(opfDoc)

        // Extract manifest (id -> href mapping)
        val opfBasePath = opfPath.substringBeforeLast("/", "")
        val manifest = mutableMapOf<String, String>()
        opfDoc.select("manifest item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                val fullHref = if (opfBasePath.isNotBlank()) "$opfBasePath/$href" else href
                manifest[id] = fullHref
            }
        }

        // Extract spine (reading order)
        val spine = mutableListOf<String>()
        opfDoc.select("spine itemref").forEach { itemref ->
            val idref = itemref.attr("idref")
            manifest[idref]?.let { spine.add(it) }
        }

        // Parse TOC (try toc.ncx first, then nav.xhtml)
        val toc =
                parseTocNcx(zipEntries, manifest, opfBasePath)
                        ?: parseTocNav(zipEntries, manifest, opfBasePath) ?: emptyList()

        return EpubBook(metadata = metadata, toc = toc, spine = spine, manifest = manifest)
    }

    /** Parse EPUB metadata from OPF */
    private fun parseEpubMetadata(opfDoc: Document): EpubMetadata {
        val metadata = opfDoc.select("metadata").first()
        return EpubMetadata(
                title = metadata?.select("dc|title, title")?.first()?.text() ?: "Unknown",
                author = metadata?.select("dc|creator, creator")?.first()?.text(),
                publisher = metadata?.select("dc|publisher, publisher")?.first()?.text(),
                language = metadata?.select("dc|language, language")?.first()?.text(),
                identifier = metadata?.select("dc|identifier, identifier")?.first()?.text()
        )
    }

    /** Parse TOC from toc.ncx file */
    private fun parseTocNcx(
            zipEntries: Map<String, ByteArray>,
            manifest: Map<String, String>,
            basePath: String
    ): List<EpubTocItem>? {
        // Find toc.ncx file
        val ncxPath =
                manifest.values.firstOrNull { it.endsWith("toc.ncx") || it.contains("toc.ncx") }
                        ?: return null

        android.util.Log.d(TAG, "parseTocNcx: Found ncx at: $ncxPath")

        val ncxContent = zipEntries[ncxPath] ?: return null
        val ncxDoc = Jsoup.parse(String(ncxContent), "", org.jsoup.parser.Parser.xmlParser())

        fun parseNavPoint(navPoint: org.jsoup.nodes.Element, playOrder: Int = 0): EpubTocItem {
            val id = navPoint.attr("id")
            val title = navPoint.select("navLabel text").first()?.text() ?: "Chapter"
            val rawSrc = navPoint.select("content").attr("src")
            // Handle both absolute and relative paths, strip leading slashes
            val src = if (rawSrc.startsWith("/")) rawSrc.substring(1) else rawSrc
            val href = if (basePath.isNotBlank() && !src.contains("/")) "$basePath/$src" else src

            val children =
                    navPoint.select("> navPoint").mapIndexed { index, child ->
                        parseNavPoint(child, playOrder + index + 1)
                    }

            return EpubTocItem(
                    id = id,
                    title = title,
                    href = href.substringBefore("#"),
                    playOrder = playOrder,
                    children = children
            )
        }

        // Parse all top-level navPoints - these may have children
        val topLevelNavPoints =
                ncxDoc.select("navMap > navPoint").mapIndexed { index, navPoint ->
                    parseNavPoint(navPoint, index)
                }

        android.util.Log.d(TAG, "parseTocNcx: Found ${topLevelNavPoints.size} top-level navPoints")
        topLevelNavPoints.forEach { item ->
            android.util.Log.d(TAG, "  - ${item.title} (${item.children.size} children)")
        }

        // Flatten the structure: if there's only one top-level item and it has children, use the
        // children
        val result =
                if (topLevelNavPoints.size == 1 && topLevelNavPoints[0].children.isNotEmpty()) {
                    android.util.Log.d(
                            TAG,
                            "parseTocNcx: Flattening - using children of '${topLevelNavPoints[0].title}'"
                    )
                    topLevelNavPoints[0].children
                } else {
                    topLevelNavPoints
                }

        android.util.Log.d(TAG, "parseTocNcx: Returning ${result.size} items")
        result.forEach { item ->
            android.util.Log.d(TAG, "  Final TOC: ${item.title} -> ${item.href}")
        }

        return result
    }

    /** Parse TOC from nav.xhtml file (EPUB 3) */
    private fun parseTocNav(
            zipEntries: Map<String, ByteArray>,
            manifest: Map<String, String>,
            basePath: String
    ): List<EpubTocItem>? {
        // Find nav.xhtml or similar
        val navPath =
                manifest.values.firstOrNull {
                    it.contains("nav.xhtml") || it.contains("nav.html") || it.endsWith("nav.xhtml")
                }
                        ?: return null

        val navContent = zipEntries[navPath] ?: return null
        val navDoc = Jsoup.parse(String(navContent))

        fun parseNavItem(li: org.jsoup.nodes.Element, playOrder: Int = 0): EpubTocItem? {
            val link = li.select("> a, > span > a").first() ?: return null
            val title = link.text()
            val rawHref = link.attr("href")
            val href = if (basePath.isNotBlank()) "$basePath/$rawHref" else rawHref

            val children =
                    li.select("> ol > li, > ul > li").mapIndexedNotNull { index, child ->
                        parseNavItem(child, playOrder + index + 1)
                    }

            return EpubTocItem(
                    id = "nav_$playOrder",
                    title = title,
                    href = href.substringBefore("#"),
                    playOrder = playOrder,
                    children = children
            )
        }

        return navDoc.select("nav[*|type=toc] ol > li, nav#toc ol > li").mapIndexedNotNull {
                index,
                li ->
            parseNavItem(li, index)
        }
    }

    /** Load a specific chapter from EPUB */
    private fun loadEpubChapter(
            filePath: String,
            epubBook: EpubBook,
            href: String,
            isPeeking: Boolean = false
    ): EpubChapter {
        val inputStream =
                if (filePath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(filePath))
                            ?: throw Exception("Cannot open EPUB file")
                } else {
                    File(filePath).inputStream()
                }

        var chapterContent: ByteArray? = null

        // Find and extract the specific chapter file
        ZipInputStream(inputStream).use { zipStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == href || entry.name.endsWith(href)) {
                    chapterContent = zipStream.readBytes()
                    break
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        if (chapterContent == null) {
            throw Exception("Chapter not found: $href")
        }

        val doc = Jsoup.parse(String(chapterContent!!))
        val tocItem = epubBook.findTocItemByHref(href)
        val contentElements = mutableListOf<ContentElement>()

        doc.select("body").first()?.let { body ->
            fun processElement(element: org.jsoup.nodes.Element) {
                when {
                    element.tagName() in listOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li") -> {
                        val images = element.select("img, image")
                        if (images.isNotEmpty()) {
                            element.children().forEach { child -> processElement(child) }
                            val directText = element.ownText().trim()
                            if (directText.isNotBlank() && directText.length > 1) {
                                contentElements.add(ContentElement.Text(directText))
                            }
                        } else {
                            val text = element.text().trim()
                            if (text.isNotBlank() && text.length > 1) {
                                contentElements.add(ContentElement.Text(text))
                            }
                        }
                    }
                    element.tagName() == "img" -> {
                        val src = element.attr("src")
                        if (src.isNotBlank()) {
                            val imgPath = resolveEpubPath(href, src)
                            contentElements.add(
                                    ContentElement.Image(
                                            url = "$filePath#img:$imgPath",
                                            altText = element.attr("alt"),
                                            description = element.attr("title")
                                    )
                            )
                        }
                    }
                    element.tagName() == "image" -> {
                        val imageHref = element.attr("xlink:href").ifBlank { element.attr("href") }
                        if (imageHref.isNotBlank()) {
                            val imgPath = resolveEpubPath(href, imageHref)
                            contentElements.add(
                                    ContentElement.Image(
                                            url = "$filePath#img:$imgPath",
                                            altText = element.attr("alt"),
                                            description = element.attr("title")
                                    )
                            )
                        }
                    }
                    else -> {
                        element.children().forEach { child -> processElement(child) }
                    }
                }
            }
            body.children().forEach { child -> processElement(child) }
        }

        val hasText = contentElements.any { it is ContentElement.Text }
        val hasImages = contentElements.any { it is ContentElement.Image }

        if (hasImages && !hasText && !isPeeking) {
            var nextHref = epubBook.getNextHref(href)
            val combinedContent = contentElements.toMutableList()
            var finalNextHref = epubBook.getNextHref(href)
            var nextTitle: String? = null
            var mergeCount = 0

            while (nextHref != null && nextHref != href && mergeCount < 5) {
                val nextChapter = loadEpubChapter(filePath, epubBook, nextHref, isPeeking = true)
                val nextHasText = nextChapter.content.any { it is ContentElement.Text }
                combinedContent.addAll(nextChapter.content)
                finalNextHref = nextChapter.nextHref
                if (nextChapter.title != null) {
                    nextTitle = nextChapter.title
                }
                if (nextHasText) break
                nextHref = epubBook.getNextHref(nextHref)
                mergeCount++
            }

            return EpubChapter(
                    href = href,
                    title = tocItem?.title ?: nextTitle ?: doc.title(),
                    content = combinedContent,
                    nextHref = finalNextHref,
                    previousHref = epubBook.getPreviousHref(href)
            )
        } 
        
        if (hasText && !hasImages && !isPeeking) {
            var prevHref = epubBook.getPreviousHref(href)
            val contentToPrepend = mutableListOf<ContentElement>()
            var finalPrevHref: String? = epubBook.getPreviousHref(href)
            var firstHrefInMerge: String? = null
            var mergeCount = 0

            while (prevHref != null && prevHref != href && mergeCount < 5) {
                val prevChapter = loadEpubChapter(filePath, epubBook, prevHref, isPeeking = true)
                val prevHasText = prevChapter.content.any { it is ContentElement.Text }
                val prevHasImages = prevChapter.content.any { it is ContentElement.Image }

                if (prevHasImages && !prevHasText) {
                    contentToPrepend.addAll(0, prevChapter.content)
                    finalPrevHref = epubBook.getPreviousHref(prevHref)
                    firstHrefInMerge = prevHref
                    mergeCount++
                } else {
                    break
                }
                prevHref = epubBook.getPreviousHref(prevHref)
            }

            if (contentToPrepend.isNotEmpty()) {
                val combinedContent = contentToPrepend + contentElements
                return EpubChapter(
                        href = firstHrefInMerge!!,
                        title = tocItem?.title ?: doc.title(),
                        content = combinedContent,
                        nextHref = epubBook.getNextHref(href),
                        previousHref = finalPrevHref
                )
            }
        }

        if (contentElements.isEmpty() && !isPeeking) {
            val nextHref = epubBook.getNextHref(href)
            if (nextHref != null && nextHref != href) {
                return loadEpubChapter(filePath, epubBook, nextHref)
            }
        }
        return EpubChapter(
                href = href,
                title = tocItem?.title ?: doc.title(),
                content = contentElements,
                nextHref = epubBook.getNextHref(href),
                previousHref = epubBook.getPreviousHref(href)
        )
    }

    /** Resolve relative path in EPUB */
    private fun resolveEpubPath(baseHref: String, relativePath: String): String {
        if (relativePath.startsWith("/")) return relativePath.drop(1)

        val basePath = baseHref.substringBeforeLast("/", "")
        return if (basePath.isNotBlank()) {
            "$basePath/$relativePath"
        } else {
            relativePath
        }
    }

    /** Increment chapter URL (e.g., chapter-1 -> chapter-2) */
    fun incrementChapterUrl(url: String): String? {
        return adjustChapterUrl(url, 1)
    }

    /** Decrement chapter URL (e.g., chapter-2 -> chapter-1) */
    fun decrementChapterUrl(url: String): String? {
        return adjustChapterUrl(url, -1)
    }

    /** Adjust chapter URL by delta */
    private fun adjustChapterUrl(url: String, delta: Int): String? {
        try {
            // Common patterns: chapter-1, chapter_1, chapter/1, ch1, c1, 001.html, etc.
            val patterns =
                    listOf(
                            Regex("""(chapter[-_/])(\d+)""", RegexOption.IGNORE_CASE),
                            Regex("""(ch[-_/]?)(\d+)""", RegexOption.IGNORE_CASE),
                            Regex("""(c[-_/]?)(\d+)""", RegexOption.IGNORE_CASE),
                            Regex("""(?<=/|-)(\d+)(?=\.html|\.htm|$)""", RegexOption.IGNORE_CASE),
                            Regex("""(\d+)(?=\.html|\.htm)""")
                    )

            for (pattern in patterns) {
                val match = pattern.find(url)
                if (match != null) {
                    val currentNumber = match.groupValues.last().toIntOrNull() ?: continue
                    val newNumber = currentNumber + delta

                    if (newNumber < 1) return null // Can't go below chapter 1

                    // Preserve leading zeros if present
                    val originalLength = match.groupValues.last().length
                    val newNumberStr = newNumber.toString().padStart(originalLength, '0')

                    return url.replaceRange(
                            match.range,
                            match.value.replace(match.groupValues.last(), newNumberStr)
                    )
                }
            }

            return null // No pattern matched
        } catch (e: Exception) {
            return null
        }
    }

    /** Get cached file for URL */
    private fun getCachedFile(url: String): File {
        val filename = url.hashCode().toString() + ".html"
        return File(cacheDir, filename)
    }

    /** Check if URL is cached */
    fun isCached(url: String): Boolean {
        return getCachedFile(url).exists()
    }

    /**
     * Fetch the title for a web page without fully parsing its content into paragraphs. Returns the
     * title string or null if it cannot be determined.
     */
    suspend fun fetchTitle(url: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    // Handle EPUB files (check extension or MIME type)
                    if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) {
                        val epubBook = getEpubBook(url)
                        return@withContext epubBook?.metadata?.title
                    }

                    // Handle PDF files
                    if (url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf")) {
                        return@withContext if (url.startsWith("content://")) {
                            Uri.parse(url).lastPathSegment?.substringBeforeLast(".")
                                    ?: "PDF Document"
                        } else {
                            File(url).nameWithoutExtension
                        }
                    }

                    // Handle web URLs
                    if (!url.startsWith("http")) return@withContext null
                    // If cached, parse cached file
                    val cached = getCachedFile(url)
                    val document =
                            if (cached.exists()) {
                                Jsoup.parse(cached, "UTF-8", url)
                            } else {
                                val html = downloadHtml(url)
                                cached.writeText(html)
                                Jsoup.parse(html, url)
                            }

                    document.title().takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
            }

    /**
     * Prefetch and cache a web URL (download HTML to cache) or cache a file path. For EPUB files,
     * extract and cache images. For WEB chapters, also prefetch all images found in the HTML.
     */
    suspend fun prefetch(url: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    if (url.startsWith("http")) {
                        val html = downloadHtml(url)
                        getCachedFile(url).writeText(html)

                        // Parse HTML to find and prefetch images
                        val doc = Jsoup.parse(html, url)
                        val result = parseHtmlDocument(doc, url)
                        if (result is ContentResult.Success) {
                            result.elements.filterIsInstance<ContentElement.Image>().forEach { image
                                ->
                                repositoryScope.launch {
                                    try {
                                        downloadAndCacheImage(image.url, url)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        true
                    } else if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) {
                        // For EPUB, parse and cache images
                        prefetchEpub(url)
                    } else {
                        // Local files/content URIs don't need prefetch but validate existence
                        val exists =
                                if (url.startsWith("content://") || url.startsWith("file://")) {
                                    try {
                                        val uri = Uri.parse(url)
                                        context.contentResolver.openInputStream(uri)?.close()
                                        true
                                    } catch (e: Exception) {
                                        false
                                    }
                                } else {
                                    File(url).exists()
                                }
                        exists
                    }
                } catch (e: Exception) {
                    false
                }
            }

    /** Prefetch EPUB file and cache images */
    private suspend fun prefetchEpub(filePath: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    // Parse EPUB structure
                    val epubBook =
                            epubBookCache[filePath]
                                    ?: parseEpubFile(filePath).also { epubBookCache[filePath] = it }

                    // Create cache directory for this EPUB
                    val bookId = filePath.hashCode().toString()
                    val bookCacheDir = File(epubCacheDir, bookId).apply { mkdirs() }

                    // Extract all images from EPUB
                    val inputStream =
                            if (filePath.startsWith("content://")) {
                                context.contentResolver.openInputStream(Uri.parse(filePath))
                                        ?: return@withContext false
                            } else {
                                File(filePath).inputStream()
                            }

                    ZipInputStream(inputStream).use { zipStream ->
                        var entry: ZipEntry? = zipStream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && isImageFile(entry.name)) {
                                // Save image to cache
                                val imageName = entry.name.replace("/", "_")
                                val imageFile = File(bookCacheDir, imageName)
                                imageFile.outputStream().use { output -> zipStream.copyTo(output) }
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }

                    true
                } catch (e: Exception) {
                    false
                }
            }

    /** Check if file is an image based on extension */
    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp")
    }

    /** Get cached image file for EPUB */
    fun getEpubImageFile(epubPath: String, imagePath: String): File? {
        val bookId = epubPath.hashCode().toString()
        val bookCacheDir = File(epubCacheDir, bookId)
        val imageName = imagePath.replace("/", "_")
        val imageFile = File(bookCacheDir, imageName)
        return if (imageFile.exists()) imageFile else null
    }

    /** Get EPUB book structure (for TOC display) */
    suspend fun getEpubBook(filePath: String): EpubBook? =
            withContext(Dispatchers.IO) {
                try {
                    epubBookCache[filePath]
                            ?: parseEpubFile(filePath).also { epubBookCache[filePath] = it }
                } catch (e: Exception) {
                    null
                }
            }

    /** Load specific EPUB chapter by href */
    suspend fun loadEpubChapterByHref(filePath: String, href: String): ContentResult =
            withContext(Dispatchers.IO) { loadEpubContent(filePath, href) }

    /** Load EPUB chapter with full ContentElement list (text + images) */
    suspend fun loadEpubChapterFull(filePath: String, href: String): EpubChapter? =
            withContext(Dispatchers.IO) {
                try {
                    val epubBook =
                            epubBookCache[filePath]
                                    ?: parseEpubFile(filePath).also { epubBookCache[filePath] = it }
                    loadEpubChapter(filePath, epubBook, href)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to load EPUB chapter: ${e.message}", e)
                    null
                }
            }

    /**
     * Get image bytes from EPUB file
     * @param url Format: "epubPath#img:imagePath"
     */
    suspend fun getEpubImage(url: String): ByteArray? =
            withContext(Dispatchers.IO) {
                try {
                    if (!url.contains("#img:")) return@withContext null

                    val parts = url.split("#img:", limit = 2)
                    if (parts.size != 2) return@withContext null

                    val epubPath = parts[0]
                    val imagePath = parts[1]

                    val inputStream =
                            if (epubPath.startsWith("content://")) {
                                context.contentResolver.openInputStream(Uri.parse(epubPath))
                                        ?: return@withContext null
                            } else {
                                File(epubPath).inputStream()
                            }

                    var imageBytes: ByteArray? = null
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry: ZipEntry? = zipStream.nextEntry
                        while (entry != null) {
                            if (entry.name == imagePath || entry.name.endsWith(imagePath)) {
                                imageBytes = zipStream.readBytes()
                                break
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }

                    imageBytes
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to load EPUB image: ${e.message}", e)
                    null
                }
            }

    /** Clear cache for a specific URL (HTML or EPUB) */
    suspend fun clearCache(url: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) {
                        clearEpubCache(url)
                    } else {
                        getCachedFile(url).delete()
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

    /** Clear EPUB-specific cache (images and memory) */
    suspend fun clearEpubCache(filePath: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    // Remove from memory cache
                    epubBookCache.remove(filePath)

                    // Remove from disk cache
                    val bookId = filePath.hashCode().toString()
                    val bookCacheDir = File(epubCacheDir, bookId)
                    if (bookCacheDir.exists()) {
                        bookCacheDir.deleteRecursively()
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

    /** Clear all cache */
    suspend fun clearAllCache(): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    cacheDir.deleteRecursively()
                    mediaCacheDir.deleteRecursively()
                    epubCacheDir.deleteRecursively()
                    epubBookCache.clear()

                    // Re-create directories
                    cacheDir.mkdirs()
                    mediaCacheDir.mkdirs()
                    epubCacheDir.mkdirs()
                    true
                } catch (e: Exception) {
                    false
                }
            }

    /** Get cache size in bytes */
    suspend fun getCacheSize(): Long =
            withContext(Dispatchers.IO) {
                try {
                    val htmlSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
                    val mediaSize = mediaCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
                    val epubSize = epubCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
                    htmlSize + mediaSize + epubSize
                } catch (e: Exception) {
                    0L
                }
            }

    /**
     * Extracts text from an element while preserving <br> tags as newlines.
     * Optimized to avoid expensive HTML serialization and re-parsing.
     */
    private fun extractTextPreservingLineBreaks(element: Element): String {
        // Fast path for elements without <br> tags
        if (element.selectFirst("br") == null) {
            return element.text()
        }

        val sb = StringBuilder()
        element.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) {
                    sb.append(node.text())
                } else if (node is Element && node.tagName() == "br") {
                    sb.append("\n")
                }
            }
            override fun tail(node: Node, depth: Int) {}
        })
        return sb.toString()
    }

    /**
     * Fetch image dimensions from URL without downloading the whole file.
     */
    private suspend fun fetchImageDimensions(imageUrl: String, pageUrl: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            if (!imageUrl.startsWith("http")) return@withContext null

            // Check if already cached locally first - this is the fastest way
            val cachedFile = getCachedMediaFile(imageUrl)
            if (cachedFile.exists()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(cachedFile.absolutePath, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    return@withContext Pair(options.outWidth, options.outHeight)
                }
            }

            val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
            val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl

            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", referer)
                .addHeader("Range", "bytes=0-16383")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val inputStream = response.body?.byteStream() ?: return@withContext null
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        return@withContext Pair(options.outWidth, options.outHeight)
                    }
                } else if (response.code == 416 || response.code == 403) {
                    // Range not supported or Forbidden with Range, try full download
                    val fullRequest = Request.Builder()
                        .url(imageUrl)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .addHeader("Referer", referer)
                        .build()
                    
                    okHttpClient.newCall(fullRequest).execute().use { fullResponse ->
                        if (fullResponse.isSuccessful) {
                            val inputStream = fullResponse.body?.byteStream() ?: return@withContext null
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeStream(inputStream, null, options)
                            if (options.outWidth > 0 && options.outHeight > 0) {
                                return@withContext Pair(options.outWidth, options.outHeight)
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
