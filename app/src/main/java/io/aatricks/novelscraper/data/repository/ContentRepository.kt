package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.content.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    suspend fun loadContent(url: String): ContentResult = loadContent(url, pdfResumeIndex = null)

    suspend fun loadContent(url: String, pdfResumeIndex: Int?): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            when {
                isRemoteWebUrl(url) -> webLoader.loadWebContent(url)
                isLikelyLocalResource(url) -> localLoader.loadLocalContent(url, pdfResumeIndex)
                else -> ContentResult.Error("Unsupported file type")
            }
        }.getOrElse { e ->
            ContentResult.Error("Failed to load content: ${e.message}", e as? Exception)
        }
    }

    private fun isRemoteWebUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun isLikelyLocalResource(url: String): Boolean {
        val lower = url.lowercase()
        return url.startsWith("content://") ||
                url.startsWith("file://") ||
                url.contains("/storage/") ||
                url.startsWith("/") ||
                lower.endsWith(".pdf") ||
                lower.endsWith(".epub") ||
                lower.endsWith(".html") ||
                lower.endsWith(".htm")
    }

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = 
        webLoader.downloadAndCacheImage(imageUrl, pageUrl)

    suspend fun warmImage(imageUrl: String, pageUrl: String): Boolean =
        webLoader.warmImage(imageUrl, pageUrl) != null

    fun getCachedMediaFile(url: String): File = webLoader.getCachedMediaFile(url)

    fun getReferer(url: String): String = webLoader.getReferer(url)

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

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("http") -> webLoader.prefetch(url, mode)

                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> 
                    if (epubLoader.prefetchEpub(url)) {
                        PrefetchResult(url, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true)
                    } else {
                        PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
                    }

                url.startsWith("content://") || url.startsWith("file://") -> {
                    PrefetchResult(url, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true)
                }

                else -> {
                    val exists = File(url).exists()
                    PrefetchResult(url, htmlCached = exists, totalImages = 0, cachedImages = 0, isComplete = exists)
                }
            }
        }.getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
        }
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        runCatching {
            when {
                url.startsWith("http") -> webLoader.inspectCache(url)
                url.endsWith(".epub", ignoreCase = true) || url.contains("epub") -> {
                    val cached = epubLoader.getEpubBook(url) != null
                    PrefetchResult(url, htmlCached = cached, totalImages = 0, cachedImages = 0, isComplete = cached)
                }
                url.startsWith("content://") || url.startsWith("file://") -> {
                    PrefetchResult(url, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true)
                }
                else -> {
                    val exists = File(url).exists()
                    PrefetchResult(url, htmlCached = exists, totalImages = 0, cachedImages = 0, isComplete = exists)
                }
            }
        }.getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false)
        }
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
        when {
            url.contains("epub") -> epubLoader.clearCache(url)
            url.endsWith(".pdf", ignoreCase = true) || url.contains("pdf") || url.startsWith("content://") -> {
                pdfLoader.clearCache(url)
                webLoader.clearCache(url)
            }
            else -> webLoader.clearCache(url)
        }
    }

    suspend fun clearCachesForUrls(urls: Collection<String>): Int = withContext(Dispatchers.IO) {
        coroutineScope {
            urls.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { url ->
                    async {
                        runCatching {
                            clearCache(url)
                            true
                        }.getOrDefault(false)
                    }
                }
                .toList()
                .awaitAll()
                .count { it }
        }
    }

    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            webLoader.clearAllCache()
            epubLoader.clearAllCache()
            pdfLoader.clearAllCache()
            true
        }.getOrDefault(false)
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        webLoader.getCacheSize() + epubLoader.getCacheSize()
    }
}
