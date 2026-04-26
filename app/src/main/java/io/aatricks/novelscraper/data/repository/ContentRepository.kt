package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.net.Uri
import coil3.SingletonImageLoader
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.content.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Repository for content loading and processing (Web, PDF, HTML, EPUB)
 * Acts as a Facade for specific content loaders.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val webLoader: WebContentLoader,
    private val pdfLoader: PdfContentLoader,
    private val epubLoader: EpubContentLoader,
    private val localLoader: LocalContentLoader,
    private val contentUriTypeResolver: ContentUriTypeResolver,
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private val CHAPTER_URL_PATTERNS = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
    }

    private enum class ContentKind {
        WEB,
        EPUB,
        PDF,
        HTML,
        LOCAL,
        UNKNOWN
    }

    suspend fun loadContent(url: String): ContentResult = loadContent(url, pdfResumeIndex = null)

    suspend fun loadContent(url: String, pdfResumeIndex: Int?): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.loadWebContent(url)
                ContentKind.EPUB, ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL ->
                    localLoader.loadLocalContent(url, pdfResumeIndex)
                ContentKind.UNKNOWN -> ContentResult.Error("Unsupported file type")
            }
        }.getOrElse { e ->
            ContentResult.Error("Failed to load content: ${e.message}", e as? Exception)
        }
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
            when (resolveContentKind(url)) {
                ContentKind.EPUB ->
                    epubLoader.getEpubBook(url)?.metadata?.title

                ContentKind.PDF -> {
                    if (url.startsWith("content://")) {
                        val uri = android.net.Uri.parse(url)
                        uri.lastPathSegment?.substringBeforeLast(".") ?: "PDF"
                    } else {
                        localFileNameWithoutExtension(url) ?: "PDF"
                    }
                }

                ContentKind.WEB -> webLoader.fetchTitle(url)
                else -> null
            }
        }.getOrNull()
    }

    suspend fun inferContentType(url: String): ContentType = withContext(Dispatchers.IO) {
        when (resolveContentKind(url)) {
            ContentKind.EPUB -> ContentType.EPUB
            ContentKind.PDF -> ContentType.PDF
            ContentKind.HTML -> ContentType.HTML
            else -> ContentType.WEB
        }
    }

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult = withContext(Dispatchers.IO) {
        runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.prefetch(url, mode)
                ContentKind.EPUB ->
                    if (epubLoader.prefetchEpub(url)) {
                        PrefetchResult(url, htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true, isRetryable = false)
                    } else {
                        PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = true)
                    }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL -> localContentResult(url)
                ContentKind.UNKNOWN -> PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = false)
            }
        }.getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = true)
        }
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.inspectCache(url)
                ContentKind.EPUB -> {
                    val cached = epubLoader.isCached(url)
                    PrefetchResult(url, htmlCached = cached, totalImages = 0, cachedImages = 0, isComplete = cached, isRetryable = !cached)
                }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL -> localContentResult(url)
                ContentKind.UNKNOWN -> PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = false)
            }
        }.getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = true)
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
        when (resolveContentKind(url)) {
            ContentKind.EPUB -> {
                epubLoader.clearCache(url)
                webLoader.clearCache(url)
            }
            ContentKind.PDF -> {
                pdfLoader.clearCache(url)
                webLoader.clearCache(url)
            }
            ContentKind.WEB, ContentKind.HTML, ContentKind.LOCAL -> webLoader.clearCache(url)
            ContentKind.UNKNOWN -> Unit
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
            clearHttpCache()
            clearImageCache()
            true
        }.getOrDefault(false)
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        webLoader.getCacheSize() +
            epubLoader.getCacheSize() +
            getHttpCacheSize() +
            calculateDirectorySize(File(context.cacheDir, "image_cache"))
    }

    private fun localContentResult(url: String): PrefetchResult {
        val exists = localResourceExists(url)
        return PrefetchResult(url, htmlCached = exists, totalImages = 0, cachedImages = 0, isComplete = exists, isRetryable = !exists)
    }

    private fun localResourceExists(url: String): Boolean {
        return when {
            url.startsWith("content://") -> true
            url.startsWith("file://") -> File(url.removePrefix("file://")).exists()
            else -> File(url).exists()
        }
    }

    private fun localFileNameWithoutExtension(url: String): String? {
        return when {
            url.startsWith("file://") -> File(url.removePrefix("file://")).nameWithoutExtension
            else -> File(url).nameWithoutExtension
        }?.takeIf { it.isNotBlank() }
    }

    private fun clearHttpCache() {
        val httpCacheDir = File(context.cacheDir, "http_cache")
        val httpCache = okHttpClient.cache

        if (httpCache != null) {
            runCatching { httpCache.evictAll() }
        } else {
            httpCacheDir.deleteRecursively()
        }

        httpCacheDir.mkdirs()
    }

    private fun clearImageCache() {
        runCatching {
            val imageLoader = SingletonImageLoader.get(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }

        File(context.cacheDir, "image_cache").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun getHttpCacheSize(): Long {
        val httpCacheDir = File(context.cacheDir, "http_cache")
        return runCatching { okHttpClient.cache?.size() ?: calculateDirectorySize(httpCacheDir) }
            .getOrElse { calculateDirectorySize(httpCacheDir) }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
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

    private fun resolveContentKind(url: String): ContentKind {
        return when {
            isRemoteWebUrl(url) -> ContentKind.WEB
            isLikelyLocalResource(url) -> resolveLocalContentKind(url)
            else -> ContentKind.UNKNOWN
        }
    }

    private fun resolveLocalContentKind(url: String): ContentKind {
        if (url.startsWith("content://")) {
            detectContentUriKind(url)?.let { return it }
        }

        val candidate = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("content://") -> url.substringAfterLast('/')
            else -> url
        }

        return inferLocalContentKind(candidate)
    }

    private fun detectContentUriKind(url: String): ContentKind? {
        val mime = contentUriTypeResolver.resolveMimeType(url)?.lowercase() ?: return null

        return when {
            "epub" in mime -> ContentKind.EPUB
            "pdf" in mime -> ContentKind.PDF
            "html" in mime || mime.startsWith("text/") -> ContentKind.HTML
            else -> null
        }
    }

    private fun inferLocalContentKind(candidate: String): ContentKind {
        val lower = candidate.lowercase()
        return when {
            lower.endsWith(".epub") -> ContentKind.EPUB
            lower.endsWith(".pdf") -> ContentKind.PDF
            lower.endsWith(".html") || lower.endsWith(".htm") -> ContentKind.HTML
            else -> ContentKind.LOCAL
        }
    }
}
