package io.aatricks.easyreader.data.repository.content

import android.util.Log
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.HttpRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import io.aatricks.easyreader.di.HtmlCacheDir
import io.aatricks.easyreader.di.HtmlDownloadsDir
import io.aatricks.easyreader.util.UrlSanitizer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebContentLoader @Inject constructor(
    private val htmlParser: HtmlParser,
    private val okHttpClient: OkHttpClient,
    private val imageCache: ImageCache,
    private val imageDownloader: ImageDownloader,
    @HtmlCacheDir private val cacheDir: File,
    @HtmlDownloadsDir private val downloadsDir: File
) {
    companion object {
        private const val TAG = "WebContentLoader"
        private val DIMENSION_SEMAPHORE = Semaphore(20)
        private const val MAX_CONCURRENT_DOWNLOADS = 8
        private const val NON_ESSENTIAL_TIMEOUT_SECONDS = 5L
        private const val MAX_IMAGES_PER_GROUP = 3
        private const val MAX_GROUPED_STRIP_RATIO = 4.0f
        private const val USER_REQUEST_ATTEMPTS = 4
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val USER_REQUEST_PREFETCH_PASSES = 2
        private const val FAST_PATH_USER_IMAGE_COUNT = 8
        private const val MAX_SPECULATIVE_PREFETCH_ATTEMPTS = 1
        private const val MAX_USER_PREFETCH_ATTEMPTS = 1
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
        private const val FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD = false
        private const val USER_HTML_TIMEOUT_SECONDS = 15L
        private const val MAX_PARSED_IMAGE_MEMO = 128
    }

    // Process-lifetime scope for background image prefetches that intentionally
    // outlive a single screen (e.g., speculative caching after navigating away).
    // SupervisorJob keeps one failed prefetch from cancelling the others. Per-job
    // cleanup happens in the finally blocks of the inFlight* maps.
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val userHtmlClient = okHttpClient.newBuilder()
        .callTimeout(USER_HTML_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(USER_HTML_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(USER_HTML_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val imageDownloadMutex = Mutex()
    private val chapterPrefetchMutex = Mutex()

    private data class InFlightImageDownload(
        val priority: ImageRequestPriority,
        val deferred: Deferred<ImageDownloadResult>
    )

    private data class InFlightHtmlFetch(
        val priority: ImageRequestPriority,
        val deferred: Deferred<CachedDocument>
    )

    private val inFlightImageDownloads = mutableMapOf<String, InFlightImageDownload>()
    private val inFlightChapterPrefetches = mutableMapOf<String, Deferred<PrefetchResult>>()
    private val inFlightHtmlFetches = mutableMapOf<String, InFlightHtmlFetch>()

    private data class ParsedImageMemo(
        val mtime: Long,
        val length: Long,
        val imageUrls: List<String>,
        val hasImageTags: Boolean,
        val bodyNonEmpty: Boolean
    )

    // Bounded LRU so chapter-load memos do not grow unboundedly across long sessions.
    // accessOrder=true + removeEldestEntry keeps the MAX_PARSED_IMAGE_MEMO most recently
    // touched entries. Wrapped in synchronizedMap because WebContentLoader is reentered
    // from multiple IO coroutines.
    private val parsedImageMemo: MutableMap<String, ParsedImageMemo> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, ParsedImageMemo>(
                MAX_PARSED_IMAGE_MEMO,
                0.75f,
                true
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, ParsedImageMemo>
                ): Boolean = size > MAX_PARSED_IMAGE_MEMO
            }
        )

    private data class CachedDocument(
        val document: Document,
        val fromCache: Boolean
    )

    suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        val startedAtMs = System.currentTimeMillis()
        val safeUrl = UrlSanitizer.sanitize(url)
        Log.d(TAG, "start load url=$safeUrl")
        try {
            val cachedDocument = getDocumentFromCacheOrNetwork(url, writeTier = StorageTier.CACHE)
            val document = cachedDocument.document
            Log.d(
                TAG,
                "cache/html fetch complete url=$safeUrl fromCache=${cachedDocument.fromCache} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val elements = htmlParser.parse(document, url)
            Log.d(
                TAG,
                "HTML parse complete url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val imageCount = extractImageUrls(elements).size
            Log.d(
                TAG,
                "image extraction count url=$safeUrl imageCount=$imageCount elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val canUseDiskOnlyDimensions = cachedDocument.fromCache && hasCachedMediaForAllRemoteImages(elements)
            val useDiskOnlyDimensions = canUseDiskOnlyDimensions || !FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD

            Log.d(
                TAG,
                "dimension enrichment start url=$safeUrl diskOnly=$useDiskOnlyDimensions elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            val finalElements = processChapterElements(
                elements = elements,
                url = url,
                diskOnly = useDiskOnlyDimensions
            )
            Log.d(
                TAG,
                "dimension enrichment end url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val success = ContentResult.Success(
                elements = finalElements,
                title = document.title().takeIf { it.isNotBlank() },
                url = url
            )
            Log.d(
                TAG,
                "success url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            success
        } catch (e: Exception) {
            Log.e(
                TAG,
                "error url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs} message=${e.message}",
                e
            )
            throw e
        }
    }

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult =
        prefetch(url, mode, onProgress = null)

    suspend fun prefetch(
        url: String,
        mode: PrefetchMode,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): PrefetchResult {
        val maxAttempts = if (mode == PrefetchMode.USER_REQUESTED) MAX_USER_PREFETCH_ATTEMPTS else MAX_SPECULATIVE_PREFETCH_ATTEMPTS
        var lastResult: PrefetchResult? = null

        repeat(maxAttempts) {
            val existing = chapterPrefetchMutex.withLock { inFlightChapterPrefetches[url] }
            if (existing != null) {
                val result = runCatching { existing.await() }
                    .getOrElse { inspectCache(url) }
                    .copy(isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches })

                if (result.isComplete || !result.isRetryable || mode == PrefetchMode.SPECULATIVE) return result
                lastResult = result
                return@repeat
            }

            val deferred = repositoryScope.async {
                executePrefetch(url, mode, onProgress)
            }.also { created ->
                created.invokeOnCompletion {
                    repositoryScope.launch {
                        chapterPrefetchMutex.withLock {
                            if (inFlightChapterPrefetches[url] === created) {
                                inFlightChapterPrefetches.remove(url)
                            }
                        }
                    }
                }
            }

            val active = chapterPrefetchMutex.withLock {
                val current = inFlightChapterPrefetches[url]
                if (current != null) {
                    deferred.cancel()
                    current
                } else {
                    inFlightChapterPrefetches[url] = deferred
                    deferred
                }
            }

            val result = active.await().copy(isInProgress = false)
            if (result.isComplete || !result.isRetryable || mode == PrefetchMode.SPECULATIVE) return result
            lastResult = result
        }
        return lastResult ?: inspectCache(url)
    }

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = getDocumentFromCacheOrNetwork(url, writeTier = StorageTier.CACHE).document
            doc.title().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, ImageRequestPriority.USER_REQUESTED, StorageTier.CACHE)
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun warmImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, ImageRequestPriority.SPECULATIVE, StorageTier.CACHE)
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        inspectCacheInternal(url)
    }

    suspend fun inspectDownload(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        inspectCacheInternal(url, persistentOnly = true)
    }

    fun getCachedMediaFile(url: String): File = imageCache.getCachedMediaFile(url)

    fun getCachedFile(url: String): File = findExistingCachedFile(url) ?: primaryCachedFile(url, StorageTier.CACHE)

    fun isCached(url: String): Boolean = findExistingCachedFile(url) != null

    fun isDownloaded(url: String): Boolean = primaryCachedFile(url, StorageTier.DOWNLOADS).exists()

    fun clearCache(url: String) {
        val cachedFile = findExistingCachedFile(url)
        if (cachedFile != null) {
            runCatching {
                val document = Jsoup.parse(cachedFile, "UTF-8", url)
                extractImageUrls(htmlParser.parse(document, url))
                    .distinct()
                    .forEach(imageCache::deleteCachedMediaFiles)
            }
        }
        deleteCachedHtmlFiles(url)
        parsedImageMemo.remove(url)
    }

    fun clearDownload(url: String) {
        val sourceForImageList = primaryCachedFile(url, StorageTier.DOWNLOADS)
            .takeIf(File::exists)
            ?: findExistingCachedFile(url)

        if (sourceForImageList != null) {
            runCatching {
                val document = Jsoup.parse(sourceForImageList, "UTF-8", url)
                extractImageUrls(htmlParser.parse(document, url))
                    .distinct()
                    .forEach(imageCache::deleteDownloadedMediaFile)
            }
        }

        primaryCachedFile(url, StorageTier.DOWNLOADS).delete()
        File(downloadsDir, "${CacheKeyUtils.keyFor(url)}.html.failed").delete()
        parsedImageMemo.remove(url)
    }

    suspend fun resetInFlightState(url: String) {
        chapterPrefetchMutex.withLock {
            inFlightChapterPrefetches.remove(url)
            inFlightHtmlFetches.remove(url)
        }
    }

    fun clearCachedHtml(url: String) {
        deleteCachedHtmlFiles(url)
        parsedImageMemo.remove(url)
    }

    fun clearAllCache() {
        cacheDir.deleteRecursively()
        imageCache.clearAll()
        cacheDir.mkdirs()
        parsedImageMemo.clear()
    }

    fun clearAllDownloads() {
        downloadsDir.deleteRecursively()
        imageCache.clearAllDownloads()
        downloadsDir.mkdirs()
        parsedImageMemo.clear()
    }

    fun getCacheSize(): Long {
        return FileSizeUtils.calculateDirectorySize(cacheDir) + imageCache.getCacheSize()
    }

    fun getDownloadsSize(): Long {
        return FileSizeUtils.calculateDirectorySize(downloadsDir) + imageCache.getDownloadsSize()
    }

    fun trimCaches(maxHtmlBytes: Long, maxMediaBytes: Long) {
        FileSizeUtils.trimDirectoryToSize(cacheDir, maxHtmlBytes)
        imageCache.trimToSize(maxMediaBytes)
    }

    private fun tierForPriority(priority: ImageRequestPriority): StorageTier =
        if (priority == ImageRequestPriority.USER_REQUESTED) StorageTier.DOWNLOADS else StorageTier.CACHE

    private fun tierForMode(mode: PrefetchMode): StorageTier =
        if (mode == PrefetchMode.USER_REQUESTED) StorageTier.DOWNLOADS else StorageTier.CACHE

    private suspend fun getDocumentFromCacheOrNetwork(
        url: String,
        priority: ImageRequestPriority = ImageRequestPriority.USER_REQUESTED,
        writeTier: StorageTier? = null
    ): CachedDocument {
        findExistingCachedFile(url)?.let { cachedFile ->
            return CachedDocument(
                document = Jsoup.parse(cachedFile, "UTF-8", url),
                fromCache = true
            )
        }

        val effectiveWriteTier = writeTier ?: tierForPriority(priority)

        val inFlight = chapterPrefetchMutex.withLock { inFlightHtmlFetches[url] }
        if (priority == ImageRequestPriority.USER_REQUESTED &&
            inFlight != null &&
            inFlight.priority == ImageRequestPriority.SPECULATIVE
        ) {
            runCatching { inFlight.deferred.await() }.getOrNull()?.let { return it }
            chapterPrefetchMutex.withLock {
                if (inFlightHtmlFetches[url] === inFlight) {
                    inFlightHtmlFetches.remove(url)
                }
            }
        }

        val deferred = chapterPrefetchMutex.withLock {
            inFlightHtmlFetches[url]?.deferred ?: repositoryScope.async {
                fetchAndCacheDocument(url, priority, effectiveWriteTier)
            }.also { created ->
                created.invokeOnCompletion {
                    repositoryScope.launch {
                        chapterPrefetchMutex.withLock {
                            if (inFlightHtmlFetches[url]?.deferred === created) {
                                inFlightHtmlFetches.remove(url)
                            }
                        }
                    }
                }
                inFlightHtmlFetches[url] = InFlightHtmlFetch(priority, created)
            }
        }

        return deferred.await()
    }

    private suspend fun fetchAndCacheDocument(
        url: String,
        priority: ImageRequestPriority,
        writeTier: StorageTier = tierForPriority(priority)
    ): CachedDocument {
        findExistingCachedFile(url)?.let { cachedFile ->
            return CachedDocument(
                document = Jsoup.parse(cachedFile, "UTF-8", url),
                fromCache = true
            )
        }

        val html = downloadHtml(url, priority = priority)
        val target = primaryCachedFile(url, writeTier)
        writeTextAtomically(target, html)
        return CachedDocument(
            document = Jsoup.parse(html, url),
            fromCache = false
        )
    }

    private fun promoteHtmlToDownloads(url: String): File? {
        val target = primaryCachedFile(url, StorageTier.DOWNLOADS)
        if (target.exists()) return target
        val src = legacyCachedFile(url).takeIf(File::exists)
            ?: primaryCachedFile(url, StorageTier.CACHE).takeIf(File::exists)
            ?: return null
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) return target
        return runCatching {
            src.copyTo(target, overwrite = true)
            src.delete()
            target
        }.getOrNull()
    }

    private fun extractImageUrls(elements: List<ContentElement>): List<String> {
        return elements.flatMap { element ->
            when (element) {
                is ContentElement.Image -> listOf(element.url)
                is ContentElement.ImageGroup -> element.images.map { it.url }
                else -> emptyList()
            }
        }
    }

    private fun hasCachedMediaForAllRemoteImages(elements: List<ContentElement>): Boolean {
        return extractImageUrls(elements)
            .filter { it.startsWith("http") }
            .all { imageCache.findExistingCachedMediaFile(it) != null }
    }

    private suspend fun downloadHtml(url: String, priority: ImageRequestPriority = ImageRequestPriority.USER_REQUESTED): String {
        val useShortTimeout = priority == ImageRequestPriority.SPECULATIVE
        val attempts = if (useShortTimeout) SHORT_REQUEST_ATTEMPTS else USER_REQUEST_ATTEMPTS
        val client = if (useShortTimeout) shortTimeoutClient else userHtmlClient

        var lastException: Exception? = null

        repeat(attempts) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", getReferer(url))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.string() ?: throw Exception("Empty body")
                    } else {
                        val retryAfter = HttpRetry.parseRetryAfterMs(response.header("Retry-After"))
                        if (!HttpRetry.shouldRetryResponseCode(response.code) || attempt == attempts - 1) {
                            throw Exception("HTTP ${response.code}")
                        }
                        delay(HttpRetry.nextRetryDelayMs(retryAfter, url, attempt))
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == attempts - 1 || (e.message?.startsWith("HTTP") == true && !shouldRetryException(e))) {
                    throw e
                }
                delay(HttpRetry.nextRetryDelayMs(null, url, attempt))
            }
        }
        throw lastException ?: Exception("Failed to download HTML")
    }

    private fun shouldRetryException(e: Exception): Boolean {
        val msg = e.message ?: return true
        if (msg.startsWith("HTTP ")) {
            val code = msg.removePrefix("HTTP ").toIntOrNull() ?: return true
            return HttpRetry.shouldRetryResponseCode(code)
        }
        return true
    }

    fun getReferer(url: String): String = imageDownloader.getReferer(url)

    private suspend fun processChapterElements(
        elements: List<ContentElement>,
        url: String,
        diskOnly: Boolean
    ): List<ContentElement> {
        val isLongStrip = isLongStripContent(url = url, elements = elements)
        val imageElements = elements.flatMap { element ->
            when (element) {
                is ContentElement.Image -> listOf(element)
                is ContentElement.ImageGroup -> element.images
                else -> emptyList()
            }
        }

        if (imageElements.isEmpty()) return elements

        val imagesWithDims = enrichImageDimensions(
            imageElements = imageElements,
            pageUrl = url,
            diskOnly = diskOnly
        )

        val dimMap = imageElements.zip(imagesWithDims).toMap()

        // 1. Update elements with their fetched dimensions
        val dimensionedElements = elements.map { element ->
            when (element) {
                is ContentElement.Image -> dimMap[element] ?: element
                is ContentElement.ImageGroup -> element.copy(images = element.images.map { dimMap[it] ?: it })
                else -> element
            }
        }

        if (isLongStrip) {
            return dimensionedElements
        }

        // 2. Group adjacent images/groups before splitting wide ones
        val groupedElements = groupSimilarElements(dimensionedElements)

        return expandWideElements(groupedElements, url)
    }

    private fun isLongStripContent(url: String, elements: List<ContentElement>): Boolean {
        val isManga = url.contains("manga", ignoreCase = true) &&
            !url.contains("manhwa", ignoreCase = true) &&
            !url.contains("webtoon", ignoreCase = true)
        if (isManga) return false

        val imageCount = elements.sumOf {
            when (it) {
                is ContentElement.Image -> 1
                is ContentElement.ImageGroup -> it.images.size
                else -> 0
            }
        }
        val textCount = elements.count { it is ContentElement.Text }
        return url.contains("manhwa", ignoreCase = true) ||
            url.contains("webtoon", ignoreCase = true) ||
            (imageCount > textCount && imageCount > 2)
    }

    private suspend fun enrichImageDimensions(
        imageElements: List<ContentElement.Image>,
        pageUrl: String,
        diskOnly: Boolean
    ): List<ContentElement.Image> = withContext(Dispatchers.IO) {
        imageElements.map { img ->
            async {
                DIMENSION_SEMAPHORE.withPermit {
                    fetchImageDimensions(img.url, pageUrl, diskOnly = diskOnly)?.let { (w, h) ->
                        img.copy(width = w, height = h)
                    } ?: img
                }
            }
        }.awaitAll()
    }

    private fun expandWideElements(
        groupedElements: List<ContentElement>,
        url: String
    ): List<ContentElement> {
        val finalElements = mutableListOf<ContentElement>()
        for (element in groupedElements) {
            when (element) {
                is ContentElement.Image -> {
                    if (isWideImage(element, url)) {
                        val isManga = url.contains("manga", ignoreCase = true) && !url.contains("manhwa", ignoreCase = true)
                        if (isManga) {
                            finalElements.add(element.copy(side = ContentElement.Image.Side.RIGHT))
                            finalElements.add(element.copy(side = ContentElement.Image.Side.LEFT))
                        } else {
                            finalElements.add(element.copy(side = ContentElement.Image.Side.LEFT))
                            finalElements.add(element.copy(side = ContentElement.Image.Side.RIGHT))
                        }
                    } else {
                        finalElements.add(element)
                    }
                }
                is ContentElement.ImageGroup -> {
                    // Check if the group as a whole should be split (e.g. all images are wide)
                    val firstImg = element.images.firstOrNull()
                    if (firstImg != null && isWideImage(firstImg, url)) {
                        val isManga = url.contains("manga", ignoreCase = true) && !url.contains("manhwa", ignoreCase = true)
                        if (isManga) {
                            finalElements.add(ContentElement.ImageGroup(element.images.map { it.copy(side = ContentElement.Image.Side.RIGHT) }))
                            finalElements.add(ContentElement.ImageGroup(element.images.map { it.copy(side = ContentElement.Image.Side.LEFT) }))
                        } else {
                            finalElements.add(ContentElement.ImageGroup(element.images.map { it.copy(side = ContentElement.Image.Side.LEFT) }))
                            finalElements.add(ContentElement.ImageGroup(element.images.map { it.copy(side = ContentElement.Image.Side.RIGHT) }))
                        }
                    } else {
                        finalElements.add(element)
                    }
                }
                else -> finalElements.add(element)
            }
        }

        return finalElements
    }

    private fun isWideImage(img: ContentElement.Image, url: String): Boolean {
        return img.width > img.height * 1.6 && img.width > 1600 && img.height > 0
    }

    private suspend fun fetchImageDimensions(
        imageUrl: String,
        pageUrl: String,
        diskOnly: Boolean = false
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext null

        runCatching {
            val cached = imageCache.getCachedMediaFile(imageUrl)
            ImageBoundsParser.parse(cached)?.let { bounds ->
                return@runCatching bounds
            }

            if (diskOnly) return@withContext null

            when (
                val result = imageDownloader.executeImageRequest(
                    imageUrl = imageUrl,
                    pageUrl = pageUrl,
                    priority = ImageRequestPriority.SPECULATIVE,
                    rangeHeader = "bytes=0-${MAX_DIMENSION_SNIFF_BYTES - 1}"
                )
            ) {
                is ImageFetchResult.BoundedSuccess -> ImageBoundsParser.parse(result.bytes)
                else -> null
            }
        }.getOrNull()
    }

    private sealed interface ImageDownloadResult {
        data class Success(val file: File) : ImageDownloadResult
        data class Failure(val isRetryable: Boolean) : ImageDownloadResult
    }

    private data class ImageBatchDownloadReport(
        val total: Int,
        val succeeded: Int,
        val retryableFailures: List<String>,
        val permanentFailures: List<String>
    ) {
        val isComplete: Boolean get() = succeeded == total
        val isRetryable: Boolean get() = retryableFailures.isNotEmpty()
    }

    private suspend fun executePrefetch(
        url: String,
        mode: PrefetchMode,
        onProgress: (suspend (PrefetchResult) -> Unit)? = null
    ): PrefetchResult {
        val priority = if (mode == PrefetchMode.SPECULATIVE) ImageRequestPriority.SPECULATIVE else ImageRequestPriority.USER_REQUESTED
        val cachedDocument = try {
            getDocumentFromCacheOrNetwork(
                url = url,
                priority = priority,
                writeTier = tierForMode(mode)
            )
        } catch (e: Exception) {
            return inspectCacheInternal(
                url = url,
                persistentOnly = mode == PrefetchMode.USER_REQUESTED
            ).copy(isRetryable = shouldRetryException(e))
        }

        if (mode == PrefetchMode.USER_REQUESTED) {
            promoteHtmlToDownloads(url)
        }

        val imageUrls = extractImageUrls(htmlParser.parse(cachedDocument.document, url))
            .distinct()
            .filter { it.startsWith("http") }

        var allImagesRetryable = true
        val permanentFailuresAccumulated = mutableListOf<String>()

        val emitProgress: (suspend () -> Unit)? = if (onProgress != null) {
            {
                val cached = imageUrls.count { imageCache.findExistingCachedMediaFile(it) != null }
                onProgress(
                    PrefetchResult(
                        url = url,
                        htmlCached = true,
                        totalImages = imageUrls.size,
                        cachedImages = cached,
                        isComplete = cached == imageUrls.size && imageUrls.isNotEmpty(),
                        isInProgress = cached < imageUrls.size,
                        isRetryable = true,
                        isPersistentDownload = mode == PrefetchMode.USER_REQUESTED
                    )
                )
            }
        } else null

        val safeUrl = UrlSanitizer.sanitize(url)
        when (mode) {
            PrefetchMode.USER_REQUESTED -> {
                Log.d(TAG, "USER_REQUESTED prefetch start url=$safeUrl totalImages=${imageUrls.size}")
                emitProgress?.invoke()
                for (pass in 0 until USER_REQUEST_PREFETCH_PASSES) {
                    val knownPermanent = loadPermanentFailures(url)
                    val missingImages = imageUrls.filterNot {
                        imageCache.findExistingCachedMediaFile(it) != null || it in knownPermanent
                    }
                    if (missingImages.isEmpty()) {
                        Log.d(TAG, "USER_REQUESTED prefetch complete url=$safeUrl pass=$pass")
                        break
                    }

                    val isFirstPass = pass == 0
                    val fastPath = if (isFirstPass) missingImages.take(FAST_PATH_USER_IMAGE_COUNT) else missingImages
                    val backgroundRest = if (isFirstPass) missingImages.drop(FAST_PATH_USER_IMAGE_COUNT) else emptyList()

                    Log.d(
                        TAG,
                        "USER_REQUESTED prefetch pass=$pass fastPath=${fastPath.size} bgRest=${backgroundRest.size} url=$safeUrl"
                    )
                    val report = cacheImages(
                        imageUrls = fastPath,
                        pageUrl = url,
                        priority = ImageRequestPriority.USER_REQUESTED,
                        writeTier = StorageTier.DOWNLOADS,
                        maxConcurrency = MAX_CONCURRENT_DOWNLOADS,
                        onImageCached = emitProgress
                    )
                    permanentFailuresAccumulated.addAll(report.permanentFailures)

                    if (backgroundRest.isNotEmpty()) {
                        repositoryScope.launch {
                            val bgReport = cacheImages(
                                imageUrls = backgroundRest,
                                pageUrl = url,
                                priority = ImageRequestPriority.SPECULATIVE,
                                writeTier = StorageTier.DOWNLOADS,
                                maxConcurrency = MAX_CONCURRENT_DOWNLOADS,
                                onImageCached = emitProgress
                            )
                            if (bgReport.permanentFailures.isNotEmpty()) {
                                recordPermanentFailures(url, bgReport.permanentFailures)
                            }
                        }
                    }

                    allImagesRetryable = report.isRetryable
                    if (report.retryableFailures.isEmpty() && report.permanentFailures.isNotEmpty()) {
                        Log.d(TAG, "USER_REQUESTED prefetch stop url=$safeUrl - all remaining failures are permanent")
                        break
                    }
                    if (report.isComplete && backgroundRest.isEmpty()) break

                    if (pass < USER_REQUEST_PREFETCH_PASSES - 1) {
                        delay(250L * (pass + 1))
                    }
                }
            }

            PrefetchMode.SPECULATIVE -> {
                Log.d(TAG, "SPECULATIVE prefetch HTML-only url=$safeUrl")
                allImagesRetryable = true
            }
        }

        if (permanentFailuresAccumulated.isNotEmpty()) {
            recordPermanentFailures(url, permanentFailuresAccumulated)
        }

        val inspected = inspectCacheInternal(
            url = url,
            cachedDocument = cachedDocument.document,
            persistentOnly = mode == PrefetchMode.USER_REQUESTED
        )
        val finalResult = inspected.copy(
            isInProgress = false,
            isRetryable = allImagesRetryable && !inspected.isComplete
        )
        Log.d(TAG, "prefetch final result url=$safeUrl complete=${finalResult.isComplete} cached=${finalResult.cachedImages}/${finalResult.totalImages}")
        return finalResult
    }

    private suspend fun cacheImages(
        imageUrls: List<String>,
        pageUrl: String,
        priority: ImageRequestPriority,
        writeTier: StorageTier,
        maxConcurrency: Int,
        onImageCached: (suspend () -> Unit)? = null
    ): ImageBatchDownloadReport = supervisorScope {
        if (imageUrls.isEmpty()) return@supervisorScope ImageBatchDownloadReport(0, 0, emptyList(), emptyList())

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val deferreds = imageUrls
            .distinct()
            .map { imageUrl ->
                imageUrl to async {
                    semaphore.withPermit {
                        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, priority, writeTier)
                        if (result is ImageDownloadResult.Success) {
                            onImageCached?.invoke()
                        }
                        result
                    }
                }
            }

        var succeeded = 0
        val retryableFailures = mutableListOf<String>()
        val permanentFailures = mutableListOf<String>()

        deferreds.forEach { (url, deferred) ->
            when (val result = deferred.await()) {
                is ImageDownloadResult.Success -> succeeded++
                is ImageDownloadResult.Failure -> {
                    if (result.isRetryable) retryableFailures.add(url)
                    else permanentFailures.add(url)
                }
            }
        }

        ImageBatchDownloadReport(
            total = imageUrls.size,
            succeeded = succeeded,
            retryableFailures = retryableFailures,
            permanentFailures = permanentFailures
        )
    }

    private suspend fun downloadAndCacheImageInternal(
        imageUrl: String,
        pageUrl: String,
        priority: ImageRequestPriority,
        writeTier: StorageTier = tierForPriority(priority)
    ): ImageDownloadResult = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext ImageDownloadResult.Failure(false)

        imageCache.findExistingCachedMediaFile(imageUrl)?.let { existingFile ->
            if (writeTier == StorageTier.DOWNLOADS && !imageCache.isDownloaded(imageUrl)) {
                val promoted = imageCache.promoteToDownloads(imageUrl)
                return@withContext ImageDownloadResult.Success(promoted ?: existingFile)
            }
            return@withContext ImageDownloadResult.Success(existingFile)
        }

        val cachedFile = imageCache.destinationFile(imageUrl, writeTier)
        val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")

        if (priority == ImageRequestPriority.USER_REQUESTED) {
            val toCancel = imageDownloadMutex.withLock {
                val existing = inFlightImageDownloads[imageUrl]
                if (existing != null && existing.priority == ImageRequestPriority.SPECULATIVE) {
                    inFlightImageDownloads.remove(imageUrl)
                    existing
                } else null
            }
            toCancel?.deferred?.cancel()
        }

        val deferred = imageDownloadMutex.withLock {
            val current = inFlightImageDownloads[imageUrl]
            // Async cleanup (invokeOnCompletion → launch) can lag behind sequential calls,
            // leaving a completed deferred in the map. Drop it so the retry path runs.
            if (current != null && current.deferred.isCompleted) {
                inFlightImageDownloads.remove(imageUrl)
            }
            val active = inFlightImageDownloads[imageUrl]
            if (active != null) {
                active.deferred
            } else {
                repositoryScope.async {
                    runCatching {
                        cachedFile.parentFile?.mkdirs()
                        val result = imageDownloader.executeImageRequest(
                            imageUrl = imageUrl,
                            pageUrl = pageUrl,
                            priority = priority,
                            destinationFile = tempFile
                        )

                        when (result) {
                            is ImageFetchResult.Success -> {
                                if (tempFile.renameTo(cachedFile)) {
                                    ImageDownloadResult.Success(cachedFile)
                                } else if (cachedFile.exists()) {
                                    tempFile.delete()
                                    ImageDownloadResult.Success(cachedFile)
                                } else {
                                    tempFile.delete()
                                    ImageDownloadResult.Failure(false)
                                }
                            }
                            is ImageFetchResult.HttpError -> {
                                tempFile.delete()
                                ImageDownloadResult.Failure(result.isRetryable())
                            }
                            is ImageFetchResult.NetworkError -> {
                                tempFile.delete()
                                ImageDownloadResult.Failure(true)
                            }
                            else -> {
                                tempFile.delete()
                                ImageDownloadResult.Failure(false)
                            }
                        }
                    }.getOrElse {
                        tempFile.delete()
                        ImageDownloadResult.Failure(true)
                    }
                }.also { created ->
                    created.invokeOnCompletion {
                        repositoryScope.launch {
                            imageDownloadMutex.withLock {
                                if (inFlightImageDownloads[imageUrl]?.deferred === created) {
                                    inFlightImageDownloads.remove(imageUrl)
                                }
                            }
                        }
                    }
                    inFlightImageDownloads[imageUrl] = InFlightImageDownload(priority, created)
                }
            }
        }

        return@withContext try {
            deferred.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                ImageDownloadResult.Failure(true)
            } else {
                throw e
            }
        }
    }

    private suspend fun inspectCacheInternal(
        url: String,
        cachedDocument: Document? = null,
        persistentOnly: Boolean = false
    ): PrefetchResult {
        val downloadHtmlFile = primaryCachedFile(url, StorageTier.DOWNLOADS).takeIf(File::exists)
        val htmlFile = if (persistentOnly) downloadHtmlFile else findExistingCachedFile(url)
        val htmlCached = htmlFile != null

        val memo: ParsedImageMemo? = if (cachedDocument != null) {
            val parsed = computeParsedImageMemo(cachedDocument, url, htmlFile)
            if (htmlFile != null) parsedImageMemo[url] = parsed
            parsed
        } else if (htmlFile != null) {
            val cached = parsedImageMemo[url]
            val mtime = htmlFile.lastModified()
            val length = htmlFile.length()
            if (cached != null && cached.mtime == mtime && cached.length == length) {
                cached
            } else {
                val doc = runCatching { Jsoup.parse(htmlFile, "UTF-8", url) }.getOrNull()
                if (doc != null) {
                    val parsed = computeParsedImageMemo(doc, url, htmlFile)
                    parsedImageMemo[url] = parsed
                    parsed
                } else null
            }
        } else null

        val imageUrls = memo?.imageUrls.orEmpty()
        val cachedImages = imageUrls.count { imageUrl ->
            if (persistentOnly) {
                imageCache.isDownloaded(imageUrl)
            } else {
                imageCache.findExistingCachedMediaFile(imageUrl) != null
            }
        }

        val isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches }
        val finalComplete = when {
            !htmlCached -> false
            imageUrls.isNotEmpty() -> cachedImages == imageUrls.size
            memo?.hasImageTags == true -> false
            else -> memo?.bodyNonEmpty == true
        }

        return PrefetchResult(
            url = url,
            htmlCached = htmlCached,
            totalImages = imageUrls.size,
            cachedImages = cachedImages,
            isComplete = finalComplete,
            isInProgress = isInProgress,
            isRetryable = !finalComplete,
            isPersistentDownload = persistentOnly
        )
    }

    private fun computeParsedImageMemo(document: Document, url: String, htmlFile: File?): ParsedImageMemo {
        val urls = runCatching {
            extractImageUrls(htmlParser.parse(document, url))
                .distinct()
                .filter { it.startsWith("http") }
        }.getOrDefault(emptyList())
        val hasImageTags = document.select("img[src], image[href], image[xlink|href], source[srcset]").isNotEmpty()
        val bodyNonEmpty = document.body()?.html()?.isNotBlank() == true
        return ParsedImageMemo(
            mtime = htmlFile?.lastModified() ?: 0L,
            length = htmlFile?.length() ?: 0L,
            imageUrls = urls,
            hasImageTags = hasImageTags,
            bodyNonEmpty = bodyNonEmpty
        )
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
            when (element) {
                is ContentElement.Image -> {
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
                }
                is ContentElement.ImageGroup -> {
                    when {
                        shouldGroupWithLastImage(last, element.images.first()) -> {
                            val lastImages = if (last is ContentElement.ImageGroup) last.images else listOf(last as ContentElement.Image)
                            processed[processed.size - 1] = ContentElement.ImageGroup(lastImages + element.images)
                        }
                        else -> processed.add(element)
                    }
                }
                else -> processed.add(element)
            }
        }
        return processed
    }

    private fun shouldGroupWithLastImage(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.Image || last.width <= 0 || current.width <= 0) return false
        if (kotlin.math.abs(last.width - current.width).toFloat() / last.width > 0.05f) return false
        if (last.side != ContentElement.Image.Side.FULL || current.side != ContentElement.Image.Side.FULL) {
            return false
        }
        val lastRatio = last.height.toFloat() / last.width
        val currentRatio = current.height.toFloat() / current.width
        return (currentRatio < 1.2f || lastRatio < 1.2f) &&
            lastRatio + currentRatio < MAX_GROUPED_STRIP_RATIO
    }

    private fun shouldGroupWithLastGroup(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.ImageGroup) return false
        if (current.side != ContentElement.Image.Side.FULL) return false
        if (last.images.size >= MAX_IMAGES_PER_GROUP) return false
        val lastInGroup = last.images.last()
        if (lastInGroup.width <= 0 || current.width <= 0) return false
        if (kotlin.math.abs(lastInGroup.width - current.width).toFloat() / lastInGroup.width > 0.05f) return false
        val groupRatio = last.images.sumOf { it.height }.toFloat() / lastInGroup.width
        val currentRatio = current.height.toFloat() / current.width
        return currentRatio < 1.2f && groupRatio + currentRatio < MAX_GROUPED_STRIP_RATIO
    }

    private fun primaryCachedFile(url: String, tier: StorageTier): File {
        val dir = when (tier) {
            StorageTier.DOWNLOADS -> downloadsDir
            StorageTier.CACHE -> cacheDir
        }
        return File(dir, "${CacheKeyUtils.keyFor(url)}.html")
    }

    private fun legacyCachedFile(url: String): File =
        File(cacheDir, "${url.hashCode()}.html")

    private fun findExistingCachedFile(url: String): File? =
        cacheFileVariants(url).firstOrNull(File::exists)

    private fun deleteCachedHtmlFiles(url: String) {
        cacheFileVariants(url).forEach { it.delete() }
        // also drop the permanent-failure sidecar
        sidecarFileVariants(url).forEach { it.delete() }
    }

    private fun writeTextAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tempFile = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            tempFile.writeText(text)
            if (!tempFile.renameTo(target) && !target.exists()) {
                throw IOException("Failed to cache HTML")
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun cacheFileVariants(url: String): List<File> {
        val downloads = primaryCachedFile(url, StorageTier.DOWNLOADS)
        val cache = primaryCachedFile(url, StorageTier.CACHE)
        val legacy = legacyCachedFile(url)
        return listOf(downloads, cache, legacy).distinctBy(File::getAbsolutePath)
    }

    private fun sidecarFileVariants(url: String): List<File> =
        cacheFileVariants(url).map { File(it.parent, "${it.name}.failed") }

    private fun loadPermanentFailures(url: String): Set<String> {
        val files = sidecarFileVariants(url).filter(File::exists)
        if (files.isEmpty()) return emptySet()
        return files.flatMap { f ->
            runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        }.toSet()
    }

    private fun recordPermanentFailures(url: String, failures: List<String>) {
        if (failures.isEmpty()) return
        val htmlFile = findExistingCachedFile(url) ?: return
        val sidecar = File(htmlFile.parent, "${htmlFile.name}.failed")
        val existing = loadPermanentFailures(url)
        val combined = (existing + failures).distinct()
        runCatching {
            sidecar.parentFile?.mkdirs()
            sidecar.writeText(combined.joinToString("\n"))
        }
    }
}
