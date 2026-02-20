package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.content.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for content loading and processing (Web, PDF, HTML, EPUB)
 * Acts as a Facade for specific content loaders.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val webLoader: WebContentLoader,
    private val pdfLoader: PdfContentLoader,
    private val epubLoader: EpubContentLoader,
    private val localLoader: LocalContentLoader
) {

    companion object {
        // Optimization: Define Regex patterns as constants
        private val CHAPTER_URL_PATTERNS = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
    }

    suspend fun loadContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> webLoader.loadWebContent(url)
                isLocalFile(url) -> localLoader.handleLocalFile(url, pdfLoader, epubLoader)
                url.lowercase().endsWith(".pdf") -> pdfLoader.loadPdfContent(url)
                url.lowercase().endsWith(".epub") -> epubLoader.loadEpubContent(url)
                url.lowercase().run { endsWith(".html") || endsWith(".htm") } -> localLoader.loadHtmlFile(url)
                else -> ContentResult.Error("Unsupported file type")
            }
        }.getOrElse { e ->
            ContentResult.Error("Failed to load content: ${e.message}", e as? Exception)
        }
    }

    private fun isLocalFile(url: String): Boolean = 
        url.startsWith("content://") || url.startsWith("file://") || url.contains("/storage/")

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = 
        webLoader.downloadAndCacheImage(imageUrl, pageUrl)

    fun getCachedMediaFile(url: String): File = webLoader.getCachedMediaFile(url)

    fun isCached(url: String): Boolean = webLoader.isCached(url)

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> 
                    epubLoader.getEpubBook(url)?.metadata?.title
                
                url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") -> {
                    if (url.startsWith("content://")) {
                        val uri = android.net.Uri.parse(url)
                        uri.lastPathSegment?.substringBeforeLast(".") ?: "PDF"
                    } else {
                        File(url).nameWithoutExtension
                    }
                }
                
                url.startsWith("http") -> webLoader.fetchTitle(url)
                else -> null
            }
        }.getOrNull()
    }

    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("http") -> webLoader.prefetch(url)
                
                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> 
                    epubLoader.prefetchEpub(url)
                
                url.startsWith("content://") || url.startsWith("file://") -> {
                    // Just check if we can open it
                    true 
                }
                
                else -> File(url).exists()
            }
        }.getOrDefault(false)
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

    suspend fun getEpubBook(path: String): EpubBook? = epubLoader.getEpubBook(path)

    suspend fun loadEpubChapterFull(path: String, href: String): EpubChapter? = 
        epubLoader.loadEpubChapterFull(path, href)

    suspend fun getEpubImage(url: String): ByteArray? = epubLoader.getEpubImage(url)

    suspend fun clearCache(url: String): Unit = withContext(Dispatchers.IO) {
        if (url.contains("epub")) {
            epubLoader.clearCache(url)
        } else {
            webLoader.clearCache(url)
        }
    }

    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            webLoader.clearAllCache()
            epubLoader.clearAllCache()
            true
        }.getOrDefault(false)
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        webLoader.getCacheSize() + epubLoader.getCacheSize()
    }
}
