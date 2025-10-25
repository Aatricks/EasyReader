package io.aatricks.novelscraper.data.repository

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import io.aatricks.novelscraper.data.model.*
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Repository for content operations including web scraping, HTML/PDF parsing, and caching
 */
class ContentRepository(private val context: Context) {
    
    private val TAG = "ContentRepository"
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val cacheDir: File
        get() = File(context.cacheDir, "html_cache").apply {
            if (!exists()) mkdirs()
        }
    
    private val epubCacheDir: File
        get() = File(context.cacheDir, "epub_cache").apply {
            if (!exists()) mkdirs()
        }
    
    // In-memory cache for parsed EPUB books
    private val epubBookCache = mutableMapOf<String, EpubBook>()
    
    /**
     * Sealed class for content operation results
     */
    sealed class ContentResult {
        data class Success(
            val paragraphs: List<String>,
            val title: String? = null,
            val url: String
        ) : ContentResult()
        
        data class Error(
            val message: String,
            val exception: Exception? = null
        ) : ContentResult()
    }
    
    /**
     * Load content from URL (web or local file)
     */
    suspend fun loadContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            // Handle HTTP(S) web URLs
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return@withContext loadWebContent(url)
            }

            // Handle content:// and file:// URIs by checking MIME type where possible
            if (url.startsWith("content://") || url.startsWith("file://") || url.contains("/storage/")) {
                try {
                    val uri = android.net.Uri.parse(url)
                    val mime = context.contentResolver.getType(uri)
                    if (mime != null) {
                        return@withContext when {
                            mime.contains("pdf") -> loadPdfContent(url)
                            mime.contains("epub") || mime.contains("application/epub+zip") -> loadEpubContent(url)
                            mime.contains("html") || mime.contains("text") -> loadHtmlFile(url)
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
                url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> loadHtmlFile(url)
                else -> ContentResult.Error("Unsupported file type")
            }
        } catch (e: Exception) {
            ContentResult.Error("Failed to load content: ${e.message}", e)
        }
    }
    
    /**
     * Load web content with caching
     */
    private suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cachedFile = getCachedFile(url)
            val document = if (cachedFile.exists()) {
                Jsoup.parse(cachedFile, "UTF-8", url)
            } else {
                // Download and cache
                val html = downloadHtml(url)
                cachedFile.writeText(html)
                Jsoup.parse(html, url)
            }
            
            parseHtmlDocument(document, url)
        } catch (e: Exception) {
            ContentResult.Error("Failed to load web content: ${e.message}", e)
        }
    }
    
    /**
     * Download HTML using OkHttp
     */
    private fun downloadHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string() ?: throw Exception("Empty response body")
        }
    }
    
    /**
     * Load local HTML file
     */
    private suspend fun loadHtmlFile(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val document = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val html = reader.readText()
                    Jsoup.parse(html, uri.toString())
                } ?: return@withContext ContentResult.Error("Unable to read HTML content: $filePath")
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext ContentResult.Error("File not found: $filePath")
                }

                Jsoup.parse(file, "UTF-8")
            }

            parseHtmlDocument(document, filePath)
        } catch (e: Exception) {
            ContentResult.Error("Failed to load HTML file: ${e.message}", e)
        }
    }
    
    /**
     * Parse HTML document and extract content
     */
    private fun parseHtmlDocument(document: Document, url: String): ContentResult {
        try {
            // Extract title
            val title = document.title().takeIf { it.isNotBlank() }
            
            // Extract paragraphs from various possible containers
            val paragraphs = mutableListOf<String>()
            
            // Try common content selectors
            val contentSelectors = listOf(
                "article p",
                ".content p",
                ".post-content p",
                ".entry-content p",
                "#content p",
                "main p",
                "p"
            )
            
            for (selector in contentSelectors) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    elements.forEach { element ->
                        val text = element.text().trim()
                        if (text.isNotBlank() && text.length > 20) { // Filter short paragraphs
                            paragraphs.add(text)
                        }
                    }
                    if (paragraphs.isNotEmpty()) break
                }
            }
            
            if (paragraphs.isEmpty()) {
                return ContentResult.Error("No content found in document")
            }
            
            return ContentResult.Success(
                paragraphs = paragraphs.distinct(), // Remove duplicates
                title = title,
                url = url
            )
        } catch (e: Exception) {
            return ContentResult.Error("Failed to parse HTML: ${e.message}", e)
        }
    }
    
    /**
     * Load PDF content and extract text
     */
    private suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val paragraphs = mutableListOf<String>()

            // Support content:// URIs as well as regular file paths
            val pdfDocument = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val pdfReader = PdfReader(inputStream)
                    PdfDocument(pdfReader)
                } ?: return@withContext ContentResult.Error("PDF file not found: $filePath")
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext ContentResult.Error("PDF file not found: $filePath")
                }
                val pdfReader = PdfReader(file)
                PdfDocument(pdfReader)
            }

            try {
                for (pageNum in 1..pdfDocument.numberOfPages) {
                    val pageText = PdfTextExtractor.getTextFromPage(pdfDocument.getPage(pageNum))
                    
                    // Remove page numbers (lines with only numbers)
                    val cleanedText = pageText.lines()
                        .filterNot { line -> line.trim().matches(Regex("^\\d+$")) }
                        .joinToString("\n")
                    
                    // Split into paragraphs
                    val pageParagraphs = cleanedText.split(Regex("\n\\s*\n"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it.length > 20 }
                    
                    paragraphs.addAll(pageParagraphs)
                }
            } finally {
                pdfDocument.close()
            }
            
            if (paragraphs.isEmpty()) {
                return@withContext ContentResult.Error("No text content found in PDF")
            }
            
            // Try to extract title from filename
            val title = try {
                if (filePath.startsWith("content://")) {
                    Uri.parse(filePath).lastPathSegment ?: "PDF Document"
                } else {
                    File(filePath).nameWithoutExtension
                }
            } catch (e: Exception) {
                "PDF Document"
            }
            
            ContentResult.Success(
                paragraphs = paragraphs,
                title = title,
                url = filePath
            )
        } catch (e: Exception) {
            ContentResult.Error("Failed to load PDF: ${e.message}", e)
        }
    }
    
    /**
     * Load EPUB content and parse structure
     */
    private suspend fun loadEpubContent(filePath: String, chapterHref: String? = null): ContentResult = withContext(Dispatchers.IO) {
        try {
            // Parse EPUB if not already cached
            val epubBook = epubBookCache[filePath] ?: parseEpubFile(filePath).also {
                epubBookCache[filePath] = it
            }
            
            // If chapterHref is provided, load that specific chapter
            val href = chapterHref ?: epubBook.spine.firstOrNull() ?: return@withContext ContentResult.Error("No chapters found in EPUB")
            
            val chapter = loadEpubChapter(filePath, epubBook, href)
            
            ContentResult.Success(
                paragraphs = chapter.content.filterIsInstance<ContentElement.Text>().map { it.content },
                title = chapter.title ?: epubBook.metadata.title,
                url = "$filePath#$href"
            )
        } catch (e: Exception) {
            ContentResult.Error("Failed to load EPUB: ${e.message}", e)
        }
    }
    
    /**
     * Parse EPUB file structure
     */
    private fun parseEpubFile(filePath: String): EpubBook {
        val inputStream = if (filePath.startsWith("content://")) {
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
        val containerXml = zipEntries["META-INF/container.xml"]
            ?: throw Exception("container.xml not found in EPUB")
        
        val containerDoc = Jsoup.parse(String(containerXml), "", org.jsoup.parser.Parser.xmlParser())
        val opfPath = containerDoc.select("rootfile").attr("full-path")
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
        val toc = parseTocNcx(zipEntries, manifest, opfBasePath) 
            ?: parseTocNav(zipEntries, manifest, opfBasePath) 
            ?: emptyList()
        
        return EpubBook(
            metadata = metadata,
            toc = toc,
            spine = spine,
            manifest = manifest
        )
    }
    
    /**
     * Parse EPUB metadata from OPF
     */
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
    
    /**
     * Parse TOC from toc.ncx file
     */
    private fun parseTocNcx(zipEntries: Map<String, ByteArray>, manifest: Map<String, String>, basePath: String): List<EpubTocItem>? {
        // Find toc.ncx file
        val ncxPath = manifest.values.firstOrNull { it.endsWith("toc.ncx") || it.contains("toc.ncx") }
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
            
            val children = navPoint.select("> navPoint").mapIndexed { index, child ->
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
        val topLevelNavPoints = ncxDoc.select("navMap > navPoint").mapIndexed { index, navPoint ->
            parseNavPoint(navPoint, index)
        }
        
        android.util.Log.d(TAG, "parseTocNcx: Found ${topLevelNavPoints.size} top-level navPoints")
        topLevelNavPoints.forEach { item ->
            android.util.Log.d(TAG, "  - ${item.title} (${item.children.size} children)")
        }
        
        // Flatten the structure: if there's only one top-level item and it has children, use the children
        val result = if (topLevelNavPoints.size == 1 && topLevelNavPoints[0].children.isNotEmpty()) {
            android.util.Log.d(TAG, "parseTocNcx: Flattening - using children of '${topLevelNavPoints[0].title}'")
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
    
    /**
     * Parse TOC from nav.xhtml file (EPUB 3)
     */
    private fun parseTocNav(zipEntries: Map<String, ByteArray>, manifest: Map<String, String>, basePath: String): List<EpubTocItem>? {
        // Find nav.xhtml or similar
        val navPath = manifest.values.firstOrNull { 
            it.contains("nav.xhtml") || it.contains("nav.html") || it.endsWith("nav.xhtml")
        } ?: return null
        
        val navContent = zipEntries[navPath] ?: return null
        val navDoc = Jsoup.parse(String(navContent))
        
        fun parseNavItem(li: org.jsoup.nodes.Element, playOrder: Int = 0): EpubTocItem? {
            val link = li.select("> a, > span > a").first() ?: return null
            val title = link.text()
            val rawHref = link.attr("href")
            val href = if (basePath.isNotBlank()) "$basePath/$rawHref" else rawHref
            
            val children = li.select("> ol > li, > ul > li").mapIndexedNotNull { index, child ->
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
        
        return navDoc.select("nav[*|type=toc] ol > li, nav#toc ol > li").mapIndexedNotNull { index, li ->
            parseNavItem(li, index)
        }
    }
    
    /**
     * Load a specific chapter from EPUB
     */
    private fun loadEpubChapter(filePath: String, epubBook: EpubBook, href: String): EpubChapter {
        val inputStream = if (filePath.startsWith("content://")) {
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
        
        // Parse HTML content
        val doc = Jsoup.parse(String(chapterContent!!))
        val tocItem = epubBook.findTocItemByHref(href)
        
        // Extract content elements (text and images)
        val contentElements = mutableListOf<ContentElement>()
        
        android.util.Log.d(TAG, "loadEpubChapter: Loading chapter '$href' from $filePath")
        
        doc.select("body").first()?.let { body ->
            // Traverse the body in document order to preserve image/text positioning
            // This ensures images appear exactly where they are in the EPUB
            fun processElement(element: org.jsoup.nodes.Element) {
                when {
                    // Check if this is a text container (p, div, h1-h6, etc.)
                    element.tagName() in listOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li") -> {
                        // First check for images within this element
                        val images = element.select("img, image")
                        if (images.isNotEmpty()) {
                            // If the element contains images, process its children in order
                            element.children().forEach { child ->
                                processElement(child)
                            }
                            // Also get any direct text in this element (not in children)
                            val directText = element.ownText().trim()
                            if (directText.isNotBlank() && directText.length > 10) {
                                contentElements.add(ContentElement.Text(directText))
                                android.util.Log.d(TAG, "loadEpubChapter: Added text: ${directText.take(50)}...")
                            }
                        } else {
                            // No images, just get the text content
                            val text = element.text().trim()
                            if (text.isNotBlank() && text.length > 10) {
                                contentElements.add(ContentElement.Text(text))
                                android.util.Log.d(TAG, "loadEpubChapter: Added text: ${text.take(50)}...")
                            }
                        }
                    }
                    // Check if this is an img tag
                    element.tagName() == "img" -> {
                        val src = element.attr("src")
                        if (src.isNotBlank()) {
                            val imgPath = resolveEpubPath(href, src)
                            android.util.Log.d(TAG, "loadEpubChapter: Found img tag with src: $imgPath")
                            contentElements.add(
                                ContentElement.Image(
                                    url = "$filePath#img:$imgPath",
                                    altText = element.attr("alt"),
                                    description = element.attr("title")
                                )
                            )
                        }
                    }
                    // Check if this is an SVG image element
                    element.tagName() == "image" -> {
                        val imageHref = element.attr("xlink:href").ifBlank { element.attr("href") }
                        if (imageHref.isNotBlank()) {
                            val imgPath = resolveEpubPath(href, imageHref)
                            android.util.Log.d(TAG, "loadEpubChapter: Found SVG image with xlink:href: $imgPath")
                            contentElements.add(
                                ContentElement.Image(
                                    url = "$filePath#img:$imgPath",
                                    altText = element.attr("alt"),
                                    description = element.attr("title")
                                )
                            )
                        }
                    }
                    // For other container elements, recurse into children
                    else -> {
                        element.children().forEach { child ->
                            processElement(child)
                        }
                    }
                }
            }
            
            // Process all direct children of body in document order
            body.children().forEach { child ->
                processElement(child)
            }
            
            android.util.Log.d(TAG, "loadEpubChapter: Found ${contentElements.size} content elements in document order")
        }
        
        android.util.Log.d(TAG, "loadEpubChapter: Extracted ${contentElements.size} content elements")
        
        // If this chapter has ONLY images but no text, try to load the next chapter and combine them
        // This handles EPUBs where intro image pages are separate from text content
        val hasText = contentElements.any { it is ContentElement.Text }
        val hasImages = contentElements.any { it is ContentElement.Image }
        
        if (hasImages && !hasText) {
            android.util.Log.d(TAG, "loadEpubChapter: Chapter '$href' has only images, checking for text in next chapter")
            val nextHref = epubBook.getNextHref(href)
            if (nextHref != null && nextHref != href) {
                android.util.Log.d(TAG, "loadEpubChapter: Loading next chapter for text: $nextHref")
                val nextChapter = loadEpubChapter(filePath, epubBook, nextHref)
                // Combine: images from current chapter + content from next chapter
                val combinedContent = contentElements + nextChapter.content
                android.util.Log.d(TAG, "loadEpubChapter: Combined chapter with '$nextHref', nextHref now points to '${nextChapter.nextHref}'")
                // Use the nextHref from the recursively merged chapter to skip all intermediate merges
                return EpubChapter(
                    href = href,
                    title = tocItem?.title ?: nextChapter.title ?: doc.title(),
                    content = combinedContent,
                    nextHref = nextChapter.nextHref, // Use the final next href from the merged chain
                    previousHref = epubBook.getPreviousHref(href)
                )
            }
        }
        
        // If this chapter has NO content at all, try to load the next chapter
        if (contentElements.isEmpty()) {
            android.util.Log.d(TAG, "loadEpubChapter: Chapter '$href' has no content, trying next in spine")
            val nextHref = epubBook.getNextHref(href)
            if (nextHref != null && nextHref != href) {
                android.util.Log.d(TAG, "loadEpubChapter: Loading next chapter: $nextHref")
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
    
    /**
     * Resolve relative path in EPUB
     */
    private fun resolveEpubPath(baseHref: String, relativePath: String): String {
        if (relativePath.startsWith("/")) return relativePath.drop(1)
        
        val basePath = baseHref.substringBeforeLast("/", "")
        return if (basePath.isNotBlank()) {
            "$basePath/$relativePath"
        } else {
            relativePath
        }
    }
    
    /**
     * Increment chapter URL (e.g., chapter-1 -> chapter-2)
     */
    fun incrementChapterUrl(url: String): String? {
        return adjustChapterUrl(url, 1)
    }
    
    /**
     * Decrement chapter URL (e.g., chapter-2 -> chapter-1)
     */
    fun decrementChapterUrl(url: String): String? {
        return adjustChapterUrl(url, -1)
    }
    
    /**
     * Adjust chapter URL by delta
     */
    private fun adjustChapterUrl(url: String, delta: Int): String? {
        try {
            // Common patterns: chapter-1, chapter_1, chapter/1, ch1, c1, 001.html, etc.
            val patterns = listOf(
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
                    
                    return url.replaceRange(match.range, match.value.replace(
                        match.groupValues.last(),
                        newNumberStr
                    ))
                }
            }
            
            return null // No pattern matched
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Get cached file for URL
     */
    private fun getCachedFile(url: String): File {
        val filename = url.hashCode().toString() + ".html"
        return File(cacheDir, filename)
    }
    
    /**
     * Check if URL is cached
     */
    fun isCached(url: String): Boolean {
        return getCachedFile(url).exists()
    }

    /**
     * Fetch the title for a web page without fully parsing its content into paragraphs.
     * Returns the title string or null if it cannot be determined.
     */
    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            // Handle EPUB files (check extension or MIME type)
            if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) {
                val epubBook = getEpubBook(url)
                return@withContext epubBook?.metadata?.title
            }
            
            // Handle PDF files
            if (url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf")) {
                return@withContext if (url.startsWith("content://")) {
                    Uri.parse(url).lastPathSegment?.substringBeforeLast(".") ?: "PDF Document"
                } else {
                    File(url).nameWithoutExtension
                }
            }
            
            // Handle web URLs
            if (!url.startsWith("http")) return@withContext null
            // If cached, parse cached file
            val cached = getCachedFile(url)
            val document = if (cached.exists()) {
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
     * Prefetch and cache a web URL (download HTML to cache) or cache a file path.
     * For EPUB files, extract and cache images.
     */
    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("http")) {
                val html = downloadHtml(url)
                getCachedFile(url).writeText(html)
                true
            } else if (url.endsWith(".epub", ignoreCase = true) || url.contains("epub")) {
                // For EPUB, parse and cache images
                prefetchEpub(url)
            } else {
                // Local files/content URIs don't need prefetch but validate existence
                val exists = if (url.startsWith("content://") || url.startsWith("file://")) {
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
    
    /**
     * Prefetch EPUB file and cache images
     */
    private suspend fun prefetchEpub(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Parse EPUB structure
            val epubBook = epubBookCache[filePath] ?: parseEpubFile(filePath).also {
                epubBookCache[filePath] = it
            }
            
            // Create cache directory for this EPUB
            val bookId = filePath.hashCode().toString()
            val bookCacheDir = File(epubCacheDir, bookId).apply { mkdirs() }
            
            // Extract all images from EPUB
            val inputStream = if (filePath.startsWith("content://")) {
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
                        imageFile.outputStream().use { output ->
                            zipStream.copyTo(output)
                        }
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
    
    /**
     * Check if file is an image based on extension
     */
    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp")
    }
    
    /**
     * Get cached image file for EPUB
     */
    fun getEpubImageFile(epubPath: String, imagePath: String): File? {
        val bookId = epubPath.hashCode().toString()
        val bookCacheDir = File(epubCacheDir, bookId)
        val imageName = imagePath.replace("/", "_")
        val imageFile = File(bookCacheDir, imageName)
        return if (imageFile.exists()) imageFile else null
    }
    
    /**
     * Get EPUB book structure (for TOC display)
     */
    suspend fun getEpubBook(filePath: String): EpubBook? = withContext(Dispatchers.IO) {
        try {
            epubBookCache[filePath] ?: parseEpubFile(filePath).also {
                epubBookCache[filePath] = it
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Load specific EPUB chapter by href
     */
    suspend fun loadEpubChapterByHref(filePath: String, href: String): ContentResult = withContext(Dispatchers.IO) {
        loadEpubContent(filePath, href)
    }
    
    /**
     * Load EPUB chapter with full ContentElement list (text + images)
     */
    suspend fun loadEpubChapterFull(filePath: String, href: String): EpubChapter? = withContext(Dispatchers.IO) {
        try {
            val epubBook = epubBookCache[filePath] ?: parseEpubFile(filePath).also {
                epubBookCache[filePath] = it
            }
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
    suspend fun getEpubImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!url.contains("#img:")) return@withContext null
            
            val parts = url.split("#img:", limit = 2)
            if (parts.size != 2) return@withContext null
            
            val epubPath = parts[0]
            val imagePath = parts[1]
            
            val inputStream = if (epubPath.startsWith("content://")) {
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
    
    /**
     * Fetch title for EPUB or other content
     */
    suspend fun clearCache(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            getCachedFile(url).delete()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear all cache
     */
    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get cache size in bytes
     */
    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
