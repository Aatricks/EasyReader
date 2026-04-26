package io.aatricks.easyreader.data.repository.content

import android.util.Log
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.util.CacheKeyUtils
import kotlinx.coroutines.CoroutineScope
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
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import io.aatricks.easyreader.di.HtmlCacheDir
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class WebContentLoader @Inject constructor(
    private val htmlParser: HtmlParser,
    private val okHttpClient: OkHttpClient,
    private val imageCache: ImageCache,
    private val imageDownloader: ImageDownloader,
    @HtmlCacheDir private val cacheDir: File
) {
    companion object {
        private const val TAG = "WebContentLoader"
        private val DIMENSION_SEMAPHORE = Semaphore(10)
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_SPECULATIVE_IMAGES = 3
        private const val NON_ESSENTIAL_TIMEOUT_SECONDS = 5L
        private const val MAX_IMAGES_PER_GROUP = 3
        private const val MAX_GROUPED_STRIP_RATIO = 4.0f
        private const val USER_REQUEST_ATTEMPTS = 4
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val USER_REQUEST_PREFETCH_PASSES = 3
        private const val MAX_SPECULATIVE_PREFETCH_ATTEMPTS = 1
        private const val MAX_USER_PREFETCH_ATTEMPTS = 3
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
        private const val FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD = false
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val imageDownloadMutex = Mutex()
    private val chapterPrefetchMutex = Mutex()

    private data class InFlightImageDownload(
        val priority: ImageRequestPriority,
        val deferred: Deferred<ImageDownloadResult>
    )

    private val inFlightImageDownloads = mutableMapOf<String, InFlightImageDownload>()
    private val inFlightChapterPrefetches = mutableMapOf<String, Deferred<PrefetchResult>>()
    private val inFlightChapterPrefetchModes = mutableMapOf<String, PrefetchMode>()

    private data class CachedDocument(
        val document: Document,
        val fromCache: Boolean
    )

    suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "start load url=$url")
        try {
            val cachedDocument = getDocumentFromCacheOrNetwork(url)
            val document = cachedDocument.document
            Log.d(
                TAG,
                "cache/html fetch complete url=$url fromCache=${cachedDocument.fromCache} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val elements = htmlParser.parse(document, url)
            Log.d(
                TAG,
                "HTML parse complete url=$url elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val imageCount = extractImageUrls(elements).size
            Log.d(
                TAG,
                "image extraction count url=$url imageCount=$imageCount elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            val canUseDiskOnlyDimensions = cachedDocument.fromCache && hasCachedMediaForAllRemoteImages(elements)
            val useDiskOnlyDimensions = canUseDiskOnlyDimensions || !FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD

            Log.d(
                TAG,
                "dimension enrichment start url=$url diskOnly=$useDiskOnlyDimensions elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            val finalElements = processChapterElements(
                elements = elements,
                url = url,
                diskOnly = useDiskOnlyDimensions
            )
            Log.d(
                TAG,
                "dimension enrichment end url=$url elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            backgroundCacheImages(extractImageUrls(finalElements), url)

            val success = ContentResult.Success(
                elements = finalElements,
                title = document.title().takeIf { it.isNotBlank() },
                url = url
            )
            Log.d(
                TAG,
                "success url=$url elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            success
        } catch (e: Exception) {
            Log.e(
                TAG,
                "error url=$url elapsedMs=${System.currentTimeMillis() - startedAtMs} message=${e.message}",
                e
            )
            throw e
        }
    }

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult {
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
                executePrefetch(url, mode)
            }.also { created ->
                created.invokeOnCompletion {
                    repositoryScope.launch {
                        chapterPrefetchMutex.withLock {
                            if (inFlightChapterPrefetches[url] === created) {
                                inFlightChapterPrefetches.remove(url)
                                inFlightChapterPrefetchModes.remove(url)
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
                    inFlightChapterPrefetchModes[url] = mode
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
            val cached = getCachedFile(url)
            val doc = if (cached.exists()) {
                Jsoup.parse(cached, "UTF-8", url)
            } else {
                val html = downloadHtml(url)
                cached.writeText(html)
                Jsoup.parse(html, url)
            }
            doc.title().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun downloadAndCacheImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, ImageRequestPriority.USER_REQUESTED)
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun warmImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, ImageRequestPriority.SPECULATIVE)
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        inspectCacheInternal(url)
    }

    fun getCachedMediaFile(url: String): File = imageCache.getCachedMediaFile(url)

    fun getCachedFile(url: String): File = findExistingCachedFile(url) ?: primaryCachedFile(url)

    fun isCached(url: String): Boolean = findExistingCachedFile(url) != null

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
    }

    suspend fun resetInFlightState(url: String) {
        chapterPrefetchMutex.withLock {
            inFlightChapterPrefetches.remove(url)
            inFlightChapterPrefetchModes.remove(url)
        }
    }

    fun clearCachedHtml(url: String) {
        deleteCachedHtmlFiles(url)
    }

    fun clearAllCache() {
        cacheDir.deleteRecursively()
        imageCache.clearAll()
        cacheDir.mkdirs()
    }

    fun getCacheSize(): Long {
        return calculateDirectorySize(cacheDir) + calculateDirectorySize(imageCache.getCachedMediaFile("").parentFile!!)
    }

    private suspend fun getDocumentFromCacheOrNetwork(url: String, priority: ImageRequestPriority = ImageRequestPriority.USER_REQUESTED): CachedDocument {
        val cachedFile = findExistingCachedFile(url)
        return if (cachedFile != null) {
            CachedDocument(
                document = Jsoup.parse(cachedFile, "UTF-8", url),
                fromCache = true
            )
        } else {
            val html = downloadHtml(url, priority = priority)
            primaryCachedFile(url).writeText(html)
            CachedDocument(
                document = Jsoup.parse(html, url),
                fromCache = false
            )
        }
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
            .all { imageCache.getCachedMediaFile(it).exists() }
    }

    private fun backgroundCacheImages(imageUrls: List<String>, pageUrl: String): Unit {
        repositoryScope.launch {
            cacheImages(
                imageUrls = imageUrls,
                pageUrl = pageUrl,
                maxImages = imageUrls.size,
                priority = ImageRequestPriority.SPECULATIVE,
                maxConcurrency = MAX_CONCURRENT_DOWNLOADS
            )
        }
    }

    private suspend fun downloadHtml(url: String, priority: ImageRequestPriority = ImageRequestPriority.USER_REQUESTED): String {
        val useShortTimeout = priority == ImageRequestPriority.SPECULATIVE
        val attempts = if (useShortTimeout) SHORT_REQUEST_ATTEMPTS else USER_REQUEST_ATTEMPTS
        val client = if (useShortTimeout) shortTimeoutClient else okHttpClient

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
                        val retryAfter = response.header("Retry-After")?.let(::parseRetryAfterMs)
                        if (!shouldRetryResponseCode(response.code) || attempt == attempts - 1) {
                            throw Exception("HTTP ${response.code}")
                        }
                        delay(nextRetryDelayMs(retryAfter, url, attempt))
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == attempts - 1 || (e is Exception && e.message?.startsWith("HTTP") == true && !shouldRetryException(e))) {
                    throw e
                }
                delay(nextRetryDelayMs(null, url, attempt))
            }
        }
        throw lastException ?: Exception("Failed to download HTML")
    }

    private fun shouldRetryException(e: Exception): Boolean {
        val msg = e.message ?: return true
        if (msg.startsWith("HTTP ")) {
            val code = msg.removePrefix("HTTP ").toIntOrNull() ?: return true
            return shouldRetryResponseCode(code)
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
        // Double-page spreads are typically twice as wide as their height (ratio ~1.4-1.7 depending on scan)
        // We use 1.6 to be safe, as single high-res pages can sometimes have slightly different ratios.
        // Also check absolute width to ensure we only split large high-res images that are likely spreads.
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

            // If disk-only mode (manhwa, large chapters), don't perform any network request.
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

    private suspend fun executePrefetch(url: String, mode: PrefetchMode): PrefetchResult {
        val priority = if (mode == PrefetchMode.SPECULATIVE) ImageRequestPriority.SPECULATIVE else ImageRequestPriority.USER_REQUESTED
        val cachedDocument = try {
            getDocumentFromCacheOrNetwork(
                url = url,
                priority = priority
            )
        } catch (e: Exception) {
            return inspectCacheInternal(url).copy(isRetryable = shouldRetryException(e))
        }

        val imageUrls = extractImageUrls(htmlParser.parse(cachedDocument.document, url))
            .distinct()
            .filter { it.startsWith("http") }

        var allImagesRetryable = true

        when (mode) {
            PrefetchMode.USER_REQUESTED -> {
                for (pass in 0 until USER_REQUEST_PREFETCH_PASSES) {
                    val missingImages = imageUrls.filterNot { imageCache.getCachedMediaFile(it).exists() }
                    if (missingImages.isEmpty()) break
                    val result = cacheImages(
                        imageUrls = missingImages,
                        pageUrl = url,
                        maxImages = missingImages.size,
                        priority = ImageRequestPriority.USER_REQUESTED,
                        maxConcurrency = MAX_CONCURRENT_DOWNLOADS
                    )
                    allImagesRetryable = result.second
                    if (!allImagesRetryable) break
                }
            }

            PrefetchMode.SPECULATIVE -> {
                val missingImages = imageUrls.filterNot { imageCache.getCachedMediaFile(it).exists() }
                val requestedImages = missingImages.take(MAX_SPECULATIVE_IMAGES)
                val result = cacheImages(
                    imageUrls = requestedImages,
                    pageUrl = url,
                    maxImages = requestedImages.size,
                    priority = ImageRequestPriority.SPECULATIVE,
                    maxConcurrency = 1
                )
                allImagesRetryable = result.second
            }
        }

        return inspectCacheInternal(url, cachedDocument.document).copy(
            isInProgress = false,
            isRetryable = allImagesRetryable
        )
    }

    private suspend fun cacheImages(
        imageUrls: List<String>,
        pageUrl: String,
        maxImages: Int,
        priority: ImageRequestPriority,
        maxConcurrency: Int
    ): Pair<Int, Boolean> = supervisorScope {
        if (imageUrls.isEmpty() || maxImages <= 0) return@supervisorScope 0 to true

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val results = imageUrls
            .distinct()
            .take(maxImages)
            .map { imageUrl ->
                async {
                    semaphore.withPermit {
                        downloadAndCacheImageInternal(imageUrl, pageUrl, priority)
                    }
                }
            }
            .awaitAll()

        val cachedCount = results.count { it is ImageDownloadResult.Success }
        val allRetryable = results.all { it is ImageDownloadResult.Success || (it as ImageDownloadResult.Failure).isRetryable }
        cachedCount to allRetryable
    }

    private suspend fun downloadAndCacheImageInternal(
        imageUrl: String,
        pageUrl: String,
        priority: ImageRequestPriority
    ): ImageDownloadResult = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext ImageDownloadResult.Failure(false)

        imageCache.findExistingCachedMediaFile(imageUrl)?.let { existingFile ->
            return@withContext ImageDownloadResult.Success(existingFile)
        }

        val cachedFile = imageCache.getCachedMediaFile(imageUrl)
        val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")

        var inFlight = imageDownloadMutex.withLock { inFlightImageDownloads[imageUrl] }

        if (priority == ImageRequestPriority.USER_REQUESTED &&
            inFlight != null &&
            inFlight.priority == ImageRequestPriority.SPECULATIVE
        ) {
            val result = inFlight.deferred.await()
            if (result is ImageDownloadResult.Success) return@withContext result

            imageDownloadMutex.withLock {
                if (inFlightImageDownloads[imageUrl] === inFlight) {
                    inFlightImageDownloads.remove(imageUrl)
                }
            }
            inFlight = null
        }

        val deferred = imageDownloadMutex.withLock {
            val current = inFlightImageDownloads[imageUrl]
            if (current != null) {
                current.deferred
            } else {
                repositoryScope.async {
                    runCatching {
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

        try {
            return@withContext deferred.await()
        } finally {
            if (deferred.isCompleted) {
                imageDownloadMutex.withLock {
                    if (inFlightImageDownloads[imageUrl]?.deferred === deferred) {
                        inFlightImageDownloads.remove(imageUrl)
                    }
                }
            }
        }
    }

    private suspend fun inspectCacheInternal(
        url: String,
        cachedDocument: Document? = null
    ): PrefetchResult {
        val htmlCached = findExistingCachedFile(url) != null
        val document = cachedDocument ?: findExistingCachedFile(url)
            ?.let { Jsoup.parse(it, "UTF-8", url) }
        val imageUrls = document?.let { doc ->
            runCatching { extractImageUrls(htmlParser.parse(doc, url)).distinct() }.getOrDefault(emptyList())
        } ?: emptyList()
        val cachedImages = imageUrls.count { imageUrl ->
            !imageUrl.startsWith("http") || imageCache.getCachedMediaFile(imageUrl).exists()
        }
        val isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches }
        val isComplete = htmlCached && cachedImages == imageUrls.size
        return PrefetchResult(
            url = url,
            htmlCached = htmlCached,
            totalImages = imageUrls.size,
            cachedImages = cachedImages,
            isComplete = isComplete,
            isInProgress = isInProgress,
            isRetryable = !isComplete
        )
    }

    private fun parseRetryAfterMs(value: String): Long? {
        val seconds = value.trim().toLongOrNull() ?: return null
        return (seconds.coerceAtLeast(1L) * 1000L)
            .coerceAtMost(ImageDownloader.MAX_HOST_THROTTLE_MS)
    }

    private fun shouldRetryResponseCode(code: Int): Boolean {
        return code == 408 || code == 429 || code in listOf(500, 502, 503, 504)
    }

    private fun nextRetryDelayMs(
        retryAfterMs: Long?,
        key: String,
        attempt: Int
    ): Long {
        if (retryAfterMs != null) return retryAfterMs
        val baseDelay = 400L * (1L shl attempt.coerceAtMost(3))
        val jitter = (key.hashCode().toLong().absoluteValue % 180L)
        return baseDelay + jitter
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
        // Allow widths within 5% to handle scanning / source inconsistencies
        if (kotlin.math.abs(last.width - current.width).toFloat() / last.width > 0.05f) return false
        if (last.side != ContentElement.Image.Side.FULL || current.side != ContentElement.Image.Side.FULL) {
            return false
        }
        val lastRatio = last.height.toFloat() / last.width
        val currentRatio = current.height.toFloat() / current.width
        // Keep short-strip continuity, but cap group height so scroll mode retains
        // enough recycling granularity for smooth movement through downloaded chapters.
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

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    size += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException?): java.nio.file.FileVisitResult {
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            // Ignore
        }
        return size
    }

    private fun primaryCachedFile(url: String): File =
        File(cacheDir, "${CacheKeyUtils.keyFor(url)}.html")

    private fun legacyCachedFile(url: String): File =
        File(cacheDir, "${url.hashCode()}.html")

    private fun findExistingCachedFile(url: String): File? =
        cacheFileVariants(primaryCachedFile(url), legacyCachedFile(url))
            .firstOrNull(File::exists)

    private fun deleteCachedHtmlFiles(url: String) {
        cacheFileVariants(primaryCachedFile(url), legacyCachedFile(url))
            .forEach { it.delete() }
    }

    private fun cacheFileVariants(primary: File, legacy: File): List<File> =
        listOf(primary, legacy).distinctBy(File::getAbsolutePath)
}
