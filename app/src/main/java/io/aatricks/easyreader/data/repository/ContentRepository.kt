package io.aatricks.easyreader.data.repository

import android.content.Context
import coil3.SingletonImageLoader
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.content.*
import io.aatricks.easyreader.data.repository.content.StorageTier
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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
        private const val WEB_CHAPTER_LOAD_TIMEOUT_MS = 25_000L
        private const val MAX_MEDIA_CACHE_BYTES = 512L * 1024L * 1024L
        private const val MAX_HTML_CACHE_BYTES = 64L * 1024L * 1024L
        private const val MAX_EPUB_CACHE_BYTES = 256L * 1024L * 1024L
        private const val MAX_PDF_CACHE_BYTES = 256L * 1024L * 1024L
        private const val CACHE_TRIM_INTERVAL_MS = 30_000L
        private const val INSPECT_MEMO_TTL_MS = 3_000L
        private const val BULK_DELETE_CONCURRENCY = 4
        private val CHAPTER_URL_PATTERNS = listOf(
            Regex("(chapter[-_/])(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(ch[-_/]?)(\\d+)", RegexOption.IGNORE_CASE)
        )
    }

    private data class InspectMemo(val result: PrefetchResult, val storedAt: Long)
    private val inspectMemo = java.util.concurrent.ConcurrentHashMap<String, InspectMemo>()
    private val lastCacheTrimAtMs = AtomicLong(0L)

    private val userDownloadsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun beginUserDownload(url: String) {
        userDownloadsInFlight.add(url)
    }

    fun endUserDownload(url: String) {
        userDownloadsInFlight.remove(url)
    }

    fun isUserDownloadInFlight(url: String): Boolean = url in userDownloadsInFlight

    private fun invalidateInspect(url: String) {
        inspectMemo.remove(url)
    }

    /**
     * Drop entries older than the TTL. Cheap iteration over a Concurrent map; called on
     * insert to keep the map from growing unboundedly across long sessions.
     */
    private fun pruneInspectMemo(now: Long) {
        val iterator = inspectMemo.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.storedAt >= INSPECT_MEMO_TTL_MS) {
                iterator.remove()
            }
        }
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
        try {
            val result = when (resolveContentKind(url)) {
                ContentKind.WEB -> withTimeout(WEB_CHAPTER_LOAD_TIMEOUT_MS) {
                    webLoader.loadWebContent(url)
                }
                ContentKind.EPUB, ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL ->
                    localLoader.loadLocalContent(url, pdfResumeIndex)
                ContentKind.UNKNOWN -> ContentResult.Error("Unsupported file type")
            }
            trimCachesInternal()
            result
        } catch (e: TimeoutCancellationException) {
            ContentResult.Error("Timed out loading chapter")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ContentResult.Error("Failed to load content: ${e.message}", e)
        }
    }

    suspend fun resetWebLoadState(url: String, clearCachedHtml: Boolean) = withContext(Dispatchers.IO) {
        if (resolveContentKind(url) != ContentKind.WEB) return@withContext
        webLoader.resetInFlightState(url)
        if (clearCachedHtml) {
            webLoader.clearCachedHtml(url)
        }
    }

    suspend fun downloadAndCacheImage(
        imageUrl: String,
        pageUrl: String
    ): File? = withContext(Dispatchers.IO) {
        webLoader.downloadAndCacheImage(imageUrl, pageUrl).also {
            trimCachesInternal()
        }
    }

    suspend fun warmImage(imageUrl: String, pageUrl: String): Boolean = withContext(Dispatchers.IO) {
        (webLoader.warmImage(imageUrl, pageUrl) != null).also {
            trimCachesInternal()
        }
    }

    fun getCachedMediaFile(url: String): File = webLoader.getCachedMediaFile(url)

    fun findUsableCachedMediaFile(url: String): File? = webLoader.findUsableCachedMediaFile(url)

    suspend fun loadLikelyMediaState(url: String): String = withContext(Dispatchers.IO) {
        webLoader.getLikelyMediaState(url)
    }

    suspend fun invalidateCachedMediaFile(imageUrl: String, pageUrl: String? = null): Unit = withContext(Dispatchers.IO) {
        webLoader.invalidateCachedMediaFile(imageUrl)
        pageUrl?.takeIf { it.isNotBlank() }?.let(::invalidateInspect)
    }

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

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult =
        prefetchWithProgress(url, mode, onProgress = null)

    suspend fun prefetchWithProgress(
        url: String,
        mode: PrefetchMode,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): PrefetchResult = withContext(Dispatchers.IO) {
        if (mode == PrefetchMode.USER_REQUESTED) {
            return@withContext downloadChapter(url, onProgress)
        }

        invalidateInspect(url)
        val result = runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.prefetch(url, mode, onProgress)
                ContentKind.EPUB -> {
                    val tier = if (mode == PrefetchMode.USER_REQUESTED) StorageTier.DOWNLOADS else StorageTier.CACHE
                    if (epubLoader.prefetchEpub(url, tier)) {
                        PrefetchResult(
                            url,
                            htmlCached = true,
                            totalImages = 0,
                            cachedImages = 0,
                            isComplete = true,
                            isRetryable = false,
                            isPersistentDownload = mode == PrefetchMode.USER_REQUESTED
                        )
                    } else {
                        PrefetchResult(
                            url,
                            htmlCached = false,
                            totalImages = 0,
                            cachedImages = 0,
                            isComplete = false,
                            isRetryable = true,
                            isPersistentDownload = mode == PrefetchMode.USER_REQUESTED
                        )
                    }
                }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL ->
                    localContentResult(url, isPersistentDownload = mode == PrefetchMode.USER_REQUESTED)
                ContentKind.UNKNOWN -> PrefetchResult(
                    url,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false,
                    isRetryable = false
                )
            }
        }.rethrowCancellation().getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = true)
        }
        trimCachesInternal()
        result
    }

    suspend fun downloadChapter(
        url: String,
        onProgress: (suspend (PrefetchResult) -> Unit)? = null
    ): PrefetchResult = withContext(Dispatchers.IO) {
        invalidateInspect(url)
        val result = runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.downloadChapter(url, onProgress)
                ContentKind.EPUB -> {
                    if (epubLoader.prefetchEpub(url, StorageTier.DOWNLOADS)) {
                        PrefetchResult(
                            url,
                            htmlCached = true,
                            totalImages = 0,
                            cachedImages = 0,
                            isComplete = true,
                            isRetryable = false,
                            isPersistentDownload = true
                        )
                    } else {
                        PrefetchResult(
                            url,
                            htmlCached = false,
                            totalImages = 0,
                            cachedImages = 0,
                            isComplete = false,
                            isRetryable = true,
                            isPersistentDownload = true
                        )
                    }
                }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL ->
                    localContentResult(url, isPersistentDownload = true)
                ContentKind.UNKNOWN -> PrefetchResult(
                    url,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false,
                    isRetryable = false,
                    isPersistentDownload = true
                )
            }
        }.rethrowCancellation().getOrElse {
            PrefetchResult(
                url,
                htmlCached = false,
                totalImages = 0,
                cachedImages = 0,
                isComplete = false,
                isRetryable = true,
                isPersistentDownload = true
            )
        }
        trimCachesInternal()
        result
    }

    suspend fun inspectDownload(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        val result = runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.inspectDownload(url)
                ContentKind.EPUB -> {
                    val downloaded = epubLoader.isDownloaded(url)
                    PrefetchResult(
                        url,
                        htmlCached = downloaded,
                        totalImages = 0,
                        cachedImages = 0,
                        isComplete = downloaded,
                        isRetryable = !downloaded,
                        isPersistentDownload = downloaded
                    )
                }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL ->
                    localContentResult(url, isPersistentDownload = true)
                ContentKind.UNKNOWN -> PrefetchResult(
                    url,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false,
                    isRetryable = false,
                    isPersistentDownload = true
                )
            }
        }.rethrowCancellation().getOrElse {
            PrefetchResult(
                url,
                htmlCached = false,
                totalImages = 0,
                cachedImages = 0,
                isComplete = false,
                isRetryable = true,
                isPersistentDownload = true
            )
        }
        result
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        inspectMemo[url]?.takeIf { now - it.storedAt < INSPECT_MEMO_TTL_MS }?.let { return@withContext it.result }

        val result = runCatching {
            when (resolveContentKind(url)) {
                ContentKind.WEB -> webLoader.inspectCache(url)
                ContentKind.EPUB -> {
                    val cached = epubLoader.isCached(url)
                    PrefetchResult(url, htmlCached = cached, totalImages = 0, cachedImages = 0, isComplete = cached, isRetryable = !cached)
                }
                ContentKind.PDF, ContentKind.HTML, ContentKind.LOCAL -> localContentResult(url)
                ContentKind.UNKNOWN -> PrefetchResult(
                    url,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false,
                    isRetryable = false
                )
            }
        }.rethrowCancellation().getOrElse {
            PrefetchResult(url, htmlCached = false, totalImages = 0, cachedImages = 0, isComplete = false, isRetryable = true)
        }

        if (!result.isInProgress) {
            pruneInspectMemo(now)
            inspectMemo[url] = InspectMemo(result, now)
        }
        result
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

    suspend fun getEpubImageFile(url: String): File? = epubLoader.getEpubImageFile(url)

    suspend fun clearCache(url: String): Unit = withContext(Dispatchers.IO) {
        invalidateInspect(url)
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

    suspend fun clearDownload(url: String): Unit = withContext(Dispatchers.IO) {
        invalidateInspect(url)
        when (resolveContentKind(url)) {
            ContentKind.EPUB -> epubLoader.clearDownload(url)
            ContentKind.WEB, ContentKind.HTML, ContentKind.LOCAL -> webLoader.clearDownload(url)
            ContentKind.PDF, ContentKind.UNKNOWN -> Unit
        }
    }

    suspend fun clearPermanentFailures(url: String): Unit = withContext(Dispatchers.IO) {
        when (resolveContentKind(url)) {
            ContentKind.WEB, ContentKind.HTML, ContentKind.LOCAL -> webLoader.clearPermanentFailures(url)
            else -> Unit
        }
    }

    suspend fun clearCachesForUrls(urls: Collection<String>): Int = withContext(Dispatchers.IO) {
        coroutineScope {
            val semaphore = Semaphore(BULK_DELETE_CONCURRENCY)
            urls.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { url ->
                    async {
                        semaphore.withPermit {
                            try {
                                clearCache(url)
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (ignored: Exception) {
                                false
                            }
                        }
                    }
                }
                .toList()
                .awaitAll()
                .count { it }
        }
    }

    suspend fun clearCachesAndDownloadsForUrls(urls: Collection<String>): Int = withContext(Dispatchers.IO) {
        coroutineScope {
            val semaphore = Semaphore(BULK_DELETE_CONCURRENCY)
            urls.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { url ->
                    async {
                        semaphore.withPermit {
                            try {
                                clearDownload(url)
                                clearCache(url)
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (ignored: Exception) {
                                false
                            }
                        }
                    }
                }
                .toList()
                .awaitAll()
                .count { it }
        }
    }

    suspend fun clearAllCache(): Unit = withContext(Dispatchers.IO) {
        inspectMemo.clear()
        webLoader.clearAllCache()
        epubLoader.clearAllCache()
        pdfLoader.clearAllCache()
        clearHttpCache()
        clearImageCache()
    }

    suspend fun clearAllDownloads(): Unit = withContext(Dispatchers.IO) {
        inspectMemo.clear()
        webLoader.clearAllDownloads()
        epubLoader.clearAllDownloads()
    }

    suspend fun clearImportedEpubs(): Unit = withContext(Dispatchers.IO) {
        val importedEpubsDir = File(context.filesDir, "imported_epubs")
        importedEpubsDir.deleteRecursively()
        importedEpubsDir.mkdirs()
    }

    suspend fun resetWebOfflinePipelineData(): Unit = withContext(Dispatchers.IO) {
        inspectMemo.clear()
        webLoader.clearAllCache()
        webLoader.clearAllDownloads()
        clearHttpCache()
        clearImageCache()
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        webLoader.getCacheSize() +
            epubLoader.getCacheSize() +
            pdfLoader.getCacheSize() +
            getHttpCacheSize()
    }

    suspend fun getDownloadsSize(): Long = withContext(Dispatchers.IO) {
        webLoader.getDownloadsSize() + epubLoader.getDownloadsSize()
    }

    suspend fun trimCaches(): Unit = withContext(Dispatchers.IO) {
        trimCachesInternal(force = true)
    }

    suspend fun sweepLegacyWebDownloadArtifacts(): Unit = withContext(Dispatchers.IO) {
        webLoader.sweepLegacyDownloadArtifacts()
    }

    private fun localContentResult(url: String, isPersistentDownload: Boolean = false): PrefetchResult {
        val exists = localResourceExists(url)
        return PrefetchResult(
            url,
            htmlCached = exists,
            totalImages = 0,
            cachedImages = 0,
            isComplete = exists,
            isRetryable = !exists,
            isPersistentDownload = isPersistentDownload && exists
        )
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
        }.takeIf { it.isNotBlank() }
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
    }

    private fun getHttpCacheSize(): Long {
        val httpCacheDir = File(context.cacheDir, "http_cache")
        return runCatching { okHttpClient.cache?.size() ?: FileSizeUtils.calculateDirectorySize(httpCacheDir) }
            .getOrElse { FileSizeUtils.calculateDirectorySize(httpCacheDir) }
    }

    private fun trimCachesInternal(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val previous = lastCacheTrimAtMs.get()
            if (now - previous < CACHE_TRIM_INTERVAL_MS) return
            if (!lastCacheTrimAtMs.compareAndSet(previous, now)) return
        } else {
            lastCacheTrimAtMs.set(now)
        }
        webLoader.trimCaches(
            maxHtmlBytes = MAX_HTML_CACHE_BYTES,
            maxMediaBytes = MAX_MEDIA_CACHE_BYTES
        )
        epubLoader.trimCache(MAX_EPUB_CACHE_BYTES)
        pdfLoader.trimCache(MAX_PDF_CACHE_BYTES)
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
