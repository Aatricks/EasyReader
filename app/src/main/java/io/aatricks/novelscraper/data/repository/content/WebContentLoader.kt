package io.aatricks.novelscraper.data.repository.content

import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.model.PrefetchMode
import io.aatricks.novelscraper.data.model.PrefetchResult
import io.aatricks.novelscraper.data.repository.HtmlParser
import io.aatricks.novelscraper.util.CacheKeyUtils
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import io.aatricks.novelscraper.di.HtmlCacheDir
import io.aatricks.novelscraper.di.MediaCacheDir
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class WebContentLoader @Inject constructor(
    private val htmlParser: HtmlParser,
    private val okHttpClient: OkHttpClient,
    @HtmlCacheDir private val cacheDir: File,
    @MediaCacheDir private val mediaCacheDir: File
) {
    companion object {
        private val DIMENSION_SEMAPHORE = Semaphore(10)
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_SPECULATIVE_IMAGES = 3
        private const val NON_ESSENTIAL_TIMEOUT_SECONDS = 5L
        private const val MAX_IMAGES_PER_GROUP = 3
        private const val MAX_GROUPED_STRIP_RATIO = 4.0f
        private const val HOST_REQUEST_SPACING_MS = 450L
        private const val HOST_RATE_LIMIT_SPACING_MS = 1200L
        private const val USER_REQUEST_ATTEMPTS = 4
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val USER_REQUEST_PREFETCH_PASSES = 3
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024L // 20MB
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val imageDownloadMutex = Mutex()
    private val chapterPrefetchMutex = Mutex()
    private val hostThrottleMutex = Mutex()
    private val inFlightImageDownloads = mutableMapOf<String, Deferred<File?>>()
    private val inFlightChapterPrefetches = mutableMapOf<String, Deferred<PrefetchResult>>()
    private val inFlightChapterPrefetchModes = mutableMapOf<String, PrefetchMode>()
    private val hostThrottleStates = mutableMapOf<String, HostThrottleState>()

    private data class CachedDocument(
        val document: Document,
        val fromCache: Boolean
    )

    private data class HostThrottleState(
        val mutex: Mutex = Mutex(),
        var nextAllowedAtMs: Long = 0L
    )

    private sealed interface ImageFetchResult {
        data class Success(val file: File) : ImageFetchResult
        data class BoundedSuccess(val bytes: ByteArray) : ImageFetchResult
        data class HttpError(val code: Int, val retryAfterMs: Long? = null) : ImageFetchResult
        data class NetworkError(val exception: IOException) : ImageFetchResult
        object TooLarge : ImageFetchResult
    }

    suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        val cachedDocument = getDocumentFromCacheOrNetwork(url)
        val document = cachedDocument.document

        val elements = htmlParser.parse(document, url)
        val canUseDiskOnlyDimensions = cachedDocument.fromCache && hasCachedMediaForAllRemoteImages(elements)
        val finalElements = processChapterElements(
            elements = elements,
            url = url,
            diskOnly = canUseDiskOnlyDimensions
        )

        backgroundCacheImages(extractImageUrls(finalElements), url)
        
        ContentResult.Success(
            elements = finalElements,
            title = document.title().takeIf { it.isNotBlank() },
            url = url
        )
    }

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult {
        while (true) {
            val existing = chapterPrefetchMutex.withLock { inFlightChapterPrefetches[url] }
            if (existing != null) {
                val result = runCatching { existing.await() }
                    .getOrElse { inspectCache(url) }
                    .copy(isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches })
                if (mode == PrefetchMode.USER_REQUESTED && !result.isComplete) continue
                return result
            }

            val deferred = repositoryScope.async {
                executePrefetch(url, mode)
            }

            val active = chapterPrefetchMutex.withLock {
                val current = inFlightChapterPrefetches[url]
                if (current != null) {
                    current
                } else {
                    inFlightChapterPrefetches[url] = deferred
                    inFlightChapterPrefetchModes[url] = mode
                    deferred
                }
            }

            if (active !== deferred) continue

            try {
                return active.await().copy(isInProgress = false)
            } finally {
                chapterPrefetchMutex.withLock {
                    if (inFlightChapterPrefetches[url] === active) {
                        inFlightChapterPrefetches.remove(url)
                        inFlightChapterPrefetchModes.remove(url)
                    }
                }
            }
        }
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
        downloadAndCacheImage(imageUrl, pageUrl, useShortTimeout = false)
    }

    suspend fun warmImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        downloadAndCacheImage(imageUrl, pageUrl, useShortTimeout = true)
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        inspectCacheInternal(url)
    }

    private suspend fun downloadAndCacheImage(
        imageUrl: String,
        pageUrl: String,
        useShortTimeout: Boolean
    ): File? = withContext(Dispatchers.IO) {
        if (!imageUrl.startsWith("http")) return@withContext null

        findExistingCachedMediaFile(imageUrl)?.let { existingFile ->
            return@withContext existingFile
        }

        val cachedFile = primaryCachedMediaFile(imageUrl)
        val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")

        val deferred = imageDownloadMutex.withLock {
            inFlightImageDownloads[imageUrl] ?: repositoryScope.async {
                runCatching {
                    val result = executeImageRequest(
                        imageUrl = imageUrl,
                        pageUrl = pageUrl,
                        useShortTimeout = useShortTimeout,
                        destinationFile = tempFile
                    )

                    when (result) {
                        is ImageFetchResult.Success -> {
                            if (tempFile.renameTo(cachedFile)) {
                                cachedFile
                            } else if (cachedFile.exists()) {
                                tempFile.delete()
                                cachedFile
                            } else {
                                tempFile.delete()
                                null
                            }
                        }

                        else -> {
                            tempFile.delete()
                            null
                        }
                    }
                }.getOrElse {
                    tempFile.delete()
                    null
                }
            }.also { inFlightImageDownloads[imageUrl] = it }
        }

        try {
            return@withContext deferred.await()
        } finally {
            if (deferred.isCompleted) {
                imageDownloadMutex.withLock {
                    if (inFlightImageDownloads[imageUrl] === deferred) {
                        inFlightImageDownloads.remove(imageUrl)
                    }
                }
            }
        }
    }

    fun getCachedMediaFile(url: String): File = findExistingCachedMediaFile(url) ?: primaryCachedMediaFile(url)

    fun getCachedFile(url: String): File = findExistingCachedFile(url) ?: primaryCachedFile(url)

    fun isCached(url: String): Boolean = findExistingCachedFile(url) != null

    fun clearCache(url: String) {
        val cachedFile = findExistingCachedFile(url)
        if (cachedFile != null) {
            runCatching {
                val document = Jsoup.parse(cachedFile, "UTF-8", url)
                extractImageUrls(htmlParser.parse(document, url))
                    .distinct()
                    .forEach(::deleteCachedMediaFiles)
            }
        }
        deleteCachedHtmlFiles(url)
    }

    fun clearAllCache() {
        cacheDir.deleteRecursively()
        mediaCacheDir.deleteRecursively()
        cacheDir.mkdirs()
        mediaCacheDir.mkdirs()
    }

    fun getCacheSize(): Long {
        return calculateDirectorySize(cacheDir) + calculateDirectorySize(mediaCacheDir)
    }

    private fun getDocumentFromCacheOrNetwork(url: String, useShortTimeout: Boolean = false): CachedDocument {
        val cachedFile = findExistingCachedFile(url)
        return if (cachedFile != null) {
            CachedDocument(
                document = Jsoup.parse(cachedFile, "UTF-8", url),
                fromCache = true
            )
        } else {
            val html = downloadHtml(url, useShortTimeout = useShortTimeout)
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
            .all { getCachedMediaFile(it).exists() }
    }

    private fun backgroundCacheImages(imageUrls: List<String>, pageUrl: String): Unit {
        repositoryScope.launch {
            cacheImages(
                imageUrls = imageUrls,
                pageUrl = pageUrl,
                maxImages = imageUrls.size,
                useShortTimeout = true,
                maxConcurrency = MAX_CONCURRENT_DOWNLOADS
            )
        }
    }

    private fun downloadHtml(url: String, useShortTimeout: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", getReferer(url))
            .build()

        val client = if (useShortTimeout) shortTimeoutClient else okHttpClient
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty body")
        }
    }

    fun getReferer(url: String): String = try {
        if (url.contains("mangabat") || url.contains("manganato")) {
            "https://manganato.com/"
        } else {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        }
    } catch (e: Exception) {
        url
    }

    private suspend fun processChapterElements(
        elements: List<ContentElement>,
        url: String,
        diskOnly: Boolean
    ): List<ContentElement> {
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

        // 2. Group adjacent images/groups before splitting wide ones
        val groupedElements = groupSimilarElements(dimensionedElements)

        return expandWideElements(groupedElements, url)
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
            val cached = getCachedMediaFile(imageUrl)
            ImageBoundsParser.parse(cached)?.let { bounds ->
                return@runCatching bounds
            }

            // If disk-only mode (manhwa, large chapters), don't perform any network request.
            if (diskOnly) return@withContext null

            when (
                val result = executeImageRequest(
                    imageUrl = imageUrl,
                    pageUrl = pageUrl,
                    useShortTimeout = true,
                    rangeHeader = "bytes=0-${MAX_DIMENSION_SNIFF_BYTES - 1}"
                )
            ) {
                is ImageFetchResult.BoundedSuccess -> {
                    val parsed = ImageBoundsParser.parse(result.bytes)
                    if (parsed != null) {
                        parsed
                    } else {
                        downloadAndCacheImage(
                            imageUrl = imageUrl,
                            pageUrl = pageUrl,
                            useShortTimeout = true
                        )?.let(ImageBoundsParser::parse)
                    }
                }

                else -> null
            }
        }.getOrNull()
    }

    private suspend fun executePrefetch(url: String, mode: PrefetchMode): PrefetchResult {
        val cachedDocument = getDocumentFromCacheOrNetwork(
            url = url,
            useShortTimeout = mode == PrefetchMode.SPECULATIVE
        )
        val imageUrls = extractImageUrls(htmlParser.parse(cachedDocument.document, url))
            .distinct()
            .filter { it.startsWith("http") }

        when (mode) {
            PrefetchMode.USER_REQUESTED -> {
                repeat(USER_REQUEST_PREFETCH_PASSES) {
                    val missingImages = imageUrls.filterNot { getCachedMediaFile(it).exists() }
                    if (missingImages.isEmpty()) return@repeat
                    cacheImages(
                        imageUrls = missingImages,
                        pageUrl = url,
                        maxImages = missingImages.size,
                        useShortTimeout = false,
                        maxConcurrency = MAX_CONCURRENT_DOWNLOADS
                    )
                }
            }

            PrefetchMode.SPECULATIVE -> {
                val missingImages = imageUrls.filterNot { getCachedMediaFile(it).exists() }
                val requestedImages = missingImages.take(MAX_SPECULATIVE_IMAGES)
                cacheImages(
                    imageUrls = requestedImages,
                    pageUrl = url,
                    maxImages = requestedImages.size,
                    useShortTimeout = true,
                    maxConcurrency = 1
                )
            }
        }

        return inspectCacheInternal(url, cachedDocument.document).copy(isInProgress = false)
    }

    private suspend fun cacheImages(
        imageUrls: List<String>,
        pageUrl: String,
        maxImages: Int,
        useShortTimeout: Boolean,
        maxConcurrency: Int
    ): Int = supervisorScope {
        if (imageUrls.isEmpty() || maxImages <= 0) return@supervisorScope 0

        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        imageUrls
            .distinct()
            .take(maxImages)
            .map { imageUrl ->
                async {
                    semaphore.withPermit {
                        if (downloadAndCacheImage(imageUrl, pageUrl, useShortTimeout) != null) 1 else 0
                    }
                }
            }
            .awaitAll()
            .sum()
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
            !imageUrl.startsWith("http") || getCachedMediaFile(imageUrl).exists()
        }
        val isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches }
        return PrefetchResult(
            url = url,
            htmlCached = htmlCached,
            totalImages = imageUrls.size,
            cachedImages = cachedImages,
            isComplete = htmlCached && cachedImages == imageUrls.size,
            isInProgress = isInProgress
        )
    }

    private suspend fun executeImageRequest(
        imageUrl: String,
        pageUrl: String,
        useShortTimeout: Boolean,
        rangeHeader: String? = null,
        destinationFile: File? = null
    ): ImageFetchResult {
        val attempts = if (useShortTimeout) SHORT_REQUEST_ATTEMPTS else USER_REQUEST_ATTEMPTS
        val client = if (useShortTimeout) shortTimeoutClient else okHttpClient

        repeat(attempts) { attempt ->
            when (
                val result = runCatching {
                    executeHostThrottled(imageUrl) {
                        val requestBuilder = Request.Builder()
                            .url(imageUrl)
                            .addHeader("User-Agent", "Mozilla/5.0")
                            .addHeader("Referer", getReferer(pageUrl))
                        if (rangeHeader != null) {
                            requestBuilder.addHeader("Range", rangeHeader)
                        }

                        client.newCall(requestBuilder.build()).execute().use { response ->
                            parseImageResponse(response, destinationFile)
                        }
                    }
                }.getOrElse { throwable ->
                    ImageFetchResult.NetworkError(throwable as? IOException ?: IOException(throwable))
                }
            ) {
                is ImageFetchResult.Success -> return result
                is ImageFetchResult.BoundedSuccess -> return result
                is ImageFetchResult.TooLarge -> return result
                is ImageFetchResult.HttpError -> {
                    if (!shouldRetryResponseCode(result.code) || attempt == attempts - 1) return result
                    delay(nextRetryDelayMs(result.retryAfterMs, imageUrl, attempt))
                }

                is ImageFetchResult.NetworkError -> {
                    if (attempt == attempts - 1) return result
                    delay(nextRetryDelayMs(null, imageUrl, attempt))
                }
            }
        }

        return ImageFetchResult.HttpError(code = 0)
    }

    private suspend fun executeHostThrottled(
        imageUrl: String,
        block: suspend () -> ImageFetchResult
    ): ImageFetchResult {
        val state = hostThrottleStateFor(imageUrl)
        return state.mutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (state.nextAllowedAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0) {
                delay(waitMs)
            }

            val result = block()
            val spacingMs = when (result) {
                is ImageFetchResult.HttpError -> {
                    val retryAfter = result.retryAfterMs
                    if (result.code == 429 && retryAfter != null) {
                        retryAfter
                    } else if (shouldRetryResponseCode(result.code)) {
                        HOST_RATE_LIMIT_SPACING_MS
                    } else {
                        HOST_REQUEST_SPACING_MS
                    }
                }

                is ImageFetchResult.NetworkError -> HOST_RATE_LIMIT_SPACING_MS
                is ImageFetchResult.Success -> HOST_REQUEST_SPACING_MS
                is ImageFetchResult.BoundedSuccess -> HOST_REQUEST_SPACING_MS
                is ImageFetchResult.TooLarge -> HOST_REQUEST_SPACING_MS
            }
            state.nextAllowedAtMs = System.currentTimeMillis() + spacingMs
            result
        }
    }

    private suspend fun hostThrottleStateFor(imageUrl: String): HostThrottleState {
        val hostKey = imageUrl.toHttpUrlOrNull()?.host?.lowercase() ?: imageUrl
        return hostThrottleMutex.withLock {
            hostThrottleStates.getOrPut(hostKey) { HostThrottleState() }
        }
    }

    private fun parseImageResponse(
        response: Response,
        destinationFile: File? = null
    ): ImageFetchResult {
        if (!response.isSuccessful) {
            return ImageFetchResult.HttpError(
                code = response.code,
                retryAfterMs = response.header("Retry-After")?.let(::parseRetryAfterMs)
            )
        }

        val body = response.body ?: return ImageFetchResult.HttpError(response.code)
        val maxBytes = if (destinationFile != null) MAX_IMAGE_BYTES else MAX_DIMENSION_SNIFF_BYTES

        val contentLength = body.contentLength()
        if (contentLength != -1L && contentLength > maxBytes) {
            return ImageFetchResult.TooLarge
        }

        return if (destinationFile != null) {
            try {
                destinationFile.sink().buffer().use { sink ->
                    val source = body.source()
                    var totalRead = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 8192)
                        if (read == -1L) break
                        totalRead += read
                        if (totalRead > maxBytes) {
                            return ImageFetchResult.TooLarge
                        }
                        sink.emitCompleteSegments()
                    }
                }
                ImageFetchResult.Success(destinationFile)
            } catch (e: Exception) {
                ImageFetchResult.NetworkError(e as? IOException ?: IOException(e))
            }
        } else {
            try {
                body.source().use { source ->
                    val buffer = okio.Buffer()
                    val read = source.read(buffer, maxBytes + 1)
                    if (read > maxBytes) {
                        ImageFetchResult.TooLarge
                    } else {
                        ImageFetchResult.BoundedSuccess(buffer.readByteArray())
                    }
                }
            } catch (e: Exception) {
                ImageFetchResult.NetworkError(e as? IOException ?: IOException(e))
            }
        }
    }

    private fun parseRetryAfterMs(value: String): Long? {
        val seconds = value.trim().toLongOrNull() ?: return null
        return seconds.coerceAtLeast(1L) * 1000L
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

    private fun primaryCachedMediaFile(url: String): File =
        File(mediaCacheDir, CacheKeyUtils.keyFor(url))

    private fun legacyCachedMediaFile(url: String): File =
        File(mediaCacheDir, url.hashCode().toString())

    private fun findExistingCachedMediaFile(url: String): File? =
        cacheFileVariants(primaryCachedMediaFile(url), legacyCachedMediaFile(url))
            .firstOrNull(File::exists)

    private fun deleteCachedMediaFiles(url: String) {
        cacheFileVariants(primaryCachedMediaFile(url), legacyCachedMediaFile(url))
            .forEach { it.delete() }
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
