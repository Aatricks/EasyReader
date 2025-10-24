package io.aatricks.novelscraper.data.repository

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Repository for content operations including web scraping, HTML/PDF parsing, and caching
 */
class ContentRepository(private val context: Context) {
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val cacheDir: File
        get() = File(context.cacheDir, "html_cache").apply {
            if (!exists()) mkdirs()
        }
    
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
     */
    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("http")) {
                val html = downloadHtml(url)
                getCachedFile(url).writeText(html)
                true
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
     * Clear specific cache
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
