package io.aatricks.easyreader.data.repository.content

import android.util.Log
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import io.aatricks.easyreader.data.repository.source.NovelightUrls
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.HttpRetry
import io.aatricks.easyreader.util.HttpTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
class WebContentLoader @Suppress("LongParameterList") @Inject constructor(
    private val htmlParser: HtmlParser,
    private val okHttpClient: OkHttpClient,
    private val imageCache: ImageCache,
    private val imageDownloader: ImageDownloader,
    private val parsedContentCache: ParsedContentCache,
    @HtmlCacheDir private val cacheDir: File,
    @HtmlDownloadsDir private val downloadsDir: File,
    private val permanentFailureStore: PermanentFailureStore,
    private val imageDimensionCache: ImageDimensionCacheRepository,
    private val offlineChapterStore: WebOfflineChapterStore,
    private val hostThrottle: HostThrottle = HostThrottle()
) {
    companion object {
        private const val TAG = "WebContentLoader"
        private val DIMENSION_SEMAPHORE = Semaphore(20)
        private const val USER_REQUEST_ATTEMPTS = 4
        private const val SHORT_REQUEST_ATTEMPTS = 2
        private const val MAX_SPECULATIVE_PREFETCH_ATTEMPTS = 1
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L // 64KB
        private const val FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD = false
        private const val MAX_PARSED_IMAGE_MEMO = 128
        // Hard cap for HTML page bodies. Real chapter pages are < 1MB; anything larger is a
        // misbehaving scraper, a CDN error page, or a hostile response — fail loud rather
        // than allocate it whole into memory via `body.string()`.
        private const val MAX_HTML_BODY_BYTES = 4L * 1024L * 1024L

        // Returned for a Novelight chapter whose prose came back empty/gated (premium) so the
        // reader shows a clean empty state instead of caching ad markup or raw JSON.
        private const val EMPTY_HTML_DOCUMENT = "<!doctype html><html><body></body></html>"
    }

    // Process-lifetime scope for background image prefetches that intentionally
    // outlive a single screen (e.g., speculative caching after navigating away).
    // SupervisorJob keeps one failed prefetch from cancelling the others. Per-job
    // cleanup happens in the finally blocks of the inFlight* maps.
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HttpTimeouts.NON_ESSENTIAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private val userHtmlClient = okHttpClient.newBuilder()
        .callTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HttpTimeouts.USER_REQUEST_SECONDS, TimeUnit.SECONDS)
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
    private val inFlightChapterPrefetches = mutableMapOf<String, InFlightChapterPrefetch>()
    private val inFlightHtmlFetches = mutableMapOf<String, InFlightHtmlFetch>()

    private data class InFlightChapterPrefetch(
        val mode: PrefetchMode,
        val deferred: Deferred<PrefetchResult>
    )

    private data class ParsedImageMemo(
        val mtime: Long,
        val length: Long,
        val imageUrls: List<String>,
        val hasImageTags: Boolean,
        val bodyNonEmpty: Boolean,
        // True when the HTML carries any of the well-known manga-reader CSS hooks (empty
        // .container-chapter-reader, img[data-page-index], Astro page-island markers, etc).
        // Used by inspectCacheInternal to refuse the "novel text page" completeness branch
        // when the page is clearly a manga reader whose pages are JS-rendered — those would
        // otherwise be marked Downloaded with zero images on disk.
        val hasMangaReaderHints: Boolean
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
            val offlineInspection = offlineChapterStore.inspectChapter(url)
            offlineChapterStore.loadContent(offlineInspection)?.let { offline ->
                Log.d(TAG, "offline manifest hit url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                return@withContext offline
            }
            if (offlineInspection.hasCompleteManifestRecord) {
                return@withContext ContentResult.Error("Downloaded chapter files are missing or corrupt")
            }
            tryLoadFromParsedCache(url, safeUrl, startedAtMs)?.let { return@withContext it }

            val cachedDocument = getDocumentFromCacheOrNetwork(url, writeTier = StorageTier.CACHE)
            Log.d(
                TAG,
                "cache/html fetch complete url=$safeUrl fromCache=${cachedDocument.fromCache} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )

            buildSuccessResult(url, safeUrl, cachedDocument, startedAtMs)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "error url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs} message=${e.message}",
                e
            )
            throw e
        }
    }

    // Fast path: HTML already on disk and parsed sidecar matches its mtime/length.
    // Skips Jsoup parse + dimension enrichment entirely. Falls through on any miss.
    private suspend fun tryLoadFromParsedCache(
        url: String,
        safeUrl: String,
        startedAtMs: Long
    ): ContentResult.Success? {
        val htmlFile = findExistingCachedFile(url) ?: return null
        val parsed = parsedContentCache.load(htmlFile) ?: return null
        val elements = enrichParsedCacheDimensions(parsed.elements)
        if (elements != parsed.elements) {
            parsedContentCache.save(htmlFile, parsed.title, elements)
        }
        Log.d(
            TAG,
            "parsed cache hit url=$safeUrl elements=${elements.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return ContentResult.Success(
            elements = elements,
            title = parsed.title,
            url = url
        )
    }

    private suspend fun enrichParsedCacheDimensions(elements: List<ContentElement>): List<ContentElement> {
        val missingUrls = elements
            .flatMap { element ->
                when (element) {
                    is ContentElement.Image ->
                        if (element.width <= 0 || element.height <= 0) listOf(element.url) else emptyList()
                    is ContentElement.ImageGroup ->
                        element.images.filter { it.width <= 0 || it.height <= 0 }.map { it.url }
                    else -> emptyList()
                }
            }
            .distinct()
        if (missingUrls.isEmpty()) return elements

        val cached = imageDimensionCache.getMany(missingUrls)
        if (cached.isEmpty()) return elements

        return elements.map { element ->
            when (element) {
                is ContentElement.Image -> {
                    val hit = cached[element.url]
                    if (hit != null && (element.width <= 0 || element.height <= 0)) {
                        element.copy(width = hit.width, height = hit.height)
                    } else {
                        element
                    }
                }

                is ContentElement.ImageGroup -> {
                    val updated = element.images.map { image ->
                        val hit = cached[image.url]
                        if (hit != null && (image.width <= 0 || image.height <= 0)) {
                            image.copy(width = hit.width, height = hit.height)
                        } else {
                            image
                        }
                    }
                    if (updated == element.images) element else element.copy(images = updated)
                }

                else -> element
            }
        }
    }

    private suspend fun buildSuccessResult(
        url: String,
        safeUrl: String,
        cachedDocument: CachedDocument,
        startedAtMs: Long
    ): ContentResult.Success {
        val document = cachedDocument.document
        val elements = htmlParser.parse(document, url)
        Log.d(TAG, "HTML parse complete url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}")

        val imageCount = extractImageUrls(elements).size
        Log.d(
            TAG,
            "image extraction count url=$safeUrl imageCount=$imageCount " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )

        val canUseDiskOnlyDimensions = cachedDocument.fromCache && hasCachedMediaForAllRemoteImages(elements)
        val useDiskOnlyDimensions = canUseDiskOnlyDimensions || !FETCH_REMOTE_DIMENSIONS_DURING_INITIAL_LOAD

        Log.d(
            TAG,
            "dimension enrichment start url=$safeUrl diskOnly=$useDiskOnlyDimensions " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        val finalElements = processChapterElements(
            elements = elements,
            url = url,
            diskOnly = useDiskOnlyDimensions
        )
        Log.d(TAG, "dimension enrichment end url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}")

        val title = document.title().takeIf { it.isNotBlank() }
        findExistingCachedFile(url)?.let { htmlFile ->
            parsedContentCache.save(htmlFile, title, finalElements)
        }

        Log.d(TAG, "success url=$safeUrl elapsedMs=${System.currentTimeMillis() - startedAtMs}")
        return ContentResult.Success(
            elements = finalElements,
            title = title,
            url = url
        )
    }

    suspend fun prefetch(url: String, mode: PrefetchMode): PrefetchResult =
        prefetch(url, mode, onProgress = null)

    suspend fun prefetch(
        url: String,
        mode: PrefetchMode,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): PrefetchResult {
        if (mode == PrefetchMode.USER_REQUESTED) {
            return downloadChapter(url, onProgress)
        }

        val maxAttempts = MAX_SPECULATIVE_PREFETCH_ATTEMPTS
        var lastResult: PrefetchResult? = null

        repeat(maxAttempts) {
            val existing = chapterPrefetchMutex.withLock {
                inFlightChapterPrefetches[url]?.takeIf { it.deferred.isCompleted }?.let {
                    inFlightChapterPrefetches.remove(url)
                }
                inFlightChapterPrefetches[url]
            }
            // A USER_REQUESTED caller must NOT reuse an in-flight SPECULATIVE prefetch:
            // SPECULATIVE only downloads HTML and would return isComplete=false without
            // ever fetching images. Awaiting it makes the user's tap "finish" without
            // doing the work. Cancel the SPECULATIVE and run a real USER_REQUESTED.
            if (existing != null && (mode == PrefetchMode.SPECULATIVE || existing.mode == PrefetchMode.USER_REQUESTED)) {
                val result = runCatching { existing.deferred.await() }
                    .getOrElse { inspectCache(url) }
                    .copy(isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches })

                if (result.isComplete || !result.isRetryable || mode == PrefetchMode.SPECULATIVE) return result
                lastResult = result
                return@repeat
            }
            if (existing != null) {
                // USER_REQUESTED arriving while a SPECULATIVE is in flight: drop the
                // speculative reservation so our fresh deferred can register and run.
                chapterPrefetchMutex.withLock {
                    if (inFlightChapterPrefetches[url]?.deferred === existing.deferred) {
                        inFlightChapterPrefetches.remove(url)
                    }
                }
                existing.deferred.cancel()
            }

            // CoroutineStart.LAZY so the deferred is created but not yet running until the
            // first .await() / .start(). Registering the URL in the inFlight map before
            // starting closes the race where a concurrent inspect could observe
            // isInProgress=false during a prefetch that has begun executing but hasn't yet
            // been added to the map.
            val deferred = repositoryScope.async(start = CoroutineStart.LAZY) {
                executePrefetch(url, mode, onProgress)
            }.also { created ->
                created.invokeOnCompletion {
                    repositoryScope.launch {
                        chapterPrefetchMutex.withLock {
                            if (inFlightChapterPrefetches[url]?.deferred === created) {
                                inFlightChapterPrefetches.remove(url)
                            }
                        }
                    }
                }
            }

            val active = chapterPrefetchMutex.withLock {
                val current = inFlightChapterPrefetches[url]
                if (current != null && current.mode == PrefetchMode.USER_REQUESTED) {
                    deferred.cancel()
                    current.deferred
                } else {
                    inFlightChapterPrefetches[url] = InFlightChapterPrefetch(mode, deferred)
                    deferred
                }
            }

            val result = active.await().copy(isInProgress = false)
            if (result.isComplete || !result.isRetryable || mode == PrefetchMode.SPECULATIVE) return result
            lastResult = result
        }
        return lastResult ?: inspectCache(url)
    }

    suspend fun downloadChapter(
        url: String,
        onProgress: (suspend (PrefetchResult) -> Unit)? = null
    ): PrefetchResult = supervisorScope {
        val deferred = async(start = CoroutineStart.LAZY) {
            executePrefetch(url, PrefetchMode.USER_REQUESTED, onProgress)
        }.also { created ->
            created.invokeOnCompletion {
                repositoryScope.launch {
                    chapterPrefetchMutex.withLock {
                        if (inFlightChapterPrefetches[url]?.deferred === created) {
                            inFlightChapterPrefetches.remove(url)
                        }
                    }
                }
            }
        }

        val active = chapterPrefetchMutex.withLock {
            // invokeOnCompletion cleanup is scheduled via repositoryScope.launch and can
            // lag behind sequential downloadChapter calls. Drop a completed deferred here
            // so a repeat USER_REQUESTED retry actually runs a fresh prefetch instead of
            // awaiting the prior already-resolved result.
            inFlightChapterPrefetches[url]?.takeIf { it.deferred.isCompleted }?.let {
                inFlightChapterPrefetches.remove(url)
            }
            val current = inFlightChapterPrefetches[url]
            if (current?.mode == PrefetchMode.USER_REQUESTED) {
                deferred.cancel()
                current.deferred
            } else {
                current?.deferred?.cancel()
                inFlightChapterPrefetches[url] = InFlightChapterPrefetch(PrefetchMode.USER_REQUESTED, deferred)
                deferred
            }
        }

        active.await().copy(isInProgress = false)
    }

    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = getDocumentFromCacheOrNetwork(url, writeTier = StorageTier.CACHE).document
            doc.title().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun downloadAndCacheImage(
        imageUrl: String,
        pageUrl: String
    ): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(
            imageUrl,
            pageUrl,
            ImageRequestPriority.USER_REQUESTED
        )
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun warmImage(imageUrl: String, pageUrl: String): File? = withContext(Dispatchers.IO) {
        val result = downloadAndCacheImageInternal(imageUrl, pageUrl, ImageRequestPriority.SPECULATIVE)
        (result as? ImageDownloadResult.Success)?.file
    }

    suspend fun inspectCache(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        inspectCacheInternal(url)
    }

    suspend fun inspectDownload(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        offlineChapterStore.inspect(url)
    }

    fun getCachedMediaFile(url: String): File = imageCache.getCachedMediaFile(url)

    fun findUsableCachedMediaFile(url: String): File? = imageCache.findExistingCachedMediaFile(url)

    fun getLikelyMediaState(url: String): String = imageCache.getLikelyMediaState(url)

    fun invalidateCachedMediaFile(url: String) {
        imageCache.deleteCachedMediaFiles(url)
    }

    fun getCachedFile(url: String): File = findExistingCachedFile(url) ?: primaryCachedFile(url, StorageTier.CACHE)

    fun isCached(url: String): Boolean = findExistingCachedFile(url) != null

    fun isDownloaded(url: String): Boolean = offlineChapterStore.hasCompleteChapter(url)

    fun isImageDownloaded(chapterUrl: String, imageUrl: String): Boolean =
        offlineChapterStore.hasImage(chapterUrl, imageUrl) || imageCache.isDownloaded(imageUrl)

    fun isImageDownloaded(imageUrl: String): Boolean = imageCache.isDownloaded(imageUrl)

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

    suspend fun clearPermanentFailures(url: String) {
        sidecarFileVariants(url).forEach { it.delete() }
        permanentFailureStore.clear(url)
    }

    suspend fun clearDownload(url: String) {
        offlineChapterStore.clear(url)
        primaryCachedFile(url, StorageTier.DOWNLOADS).delete()
        File(downloadsDir, "${CacheKeyUtils.keyFor(url)}.html.failed").delete()
        parsedImageMemo.remove(url)
        permanentFailureStore.clear(url)
    }

    fun sweepLegacyDownloadArtifacts() {
        downloadsDir.deleteRecursively()
        downloadsDir.mkdirs()
        imageCache.clearAllDownloads()
        parsedImageMemo.clear()
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
        offlineChapterStore.clearAll()
        downloadsDir.deleteRecursively()
        imageCache.clearAllDownloads()
        downloadsDir.mkdirs()
        parsedImageMemo.clear()
    }

    fun getCacheSize(): Long {
        return FileSizeUtils.calculateDirectorySize(cacheDir) + imageCache.getCacheSize()
    }

    fun getDownloadsSize(): Long {
        return FileSizeUtils.calculateDirectorySize(downloadsDir) +
            imageCache.getDownloadsSize() +
            offlineChapterStore.sizeBytes()
    }

    fun trimCaches(maxHtmlBytes: Long, maxMediaBytes: Long) {
        FileSizeUtils.trimDirectoryToSize(cacheDir, maxHtmlBytes)
        imageCache.trimToSize(maxMediaBytes)
    }

    private fun tierForPriority(priority: ImageRequestPriority): StorageTier =
        if (priority == ImageRequestPriority.USER_REQUESTED) StorageTier.DOWNLOADS else StorageTier.CACHE

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

    private sealed interface HtmlFetchOutcome {
        data class Body(val text: String) : HtmlFetchOutcome
        data class HttpError(val code: Int, val retryAfterMs: Long?) : HtmlFetchOutcome
        data class NetworkError(val cause: Exception) : HtmlFetchOutcome
    }

    private fun classifyHtmlOutcome(outcome: HtmlFetchOutcome): HostThrottle.Outcome = when (outcome) {
        is HtmlFetchOutcome.Body -> HostThrottle.Outcome.Success
        is HtmlFetchOutcome.HttpError -> when {
            outcome.code == 429 -> HostThrottle.Outcome.RateLimited(outcome.retryAfterMs)
            HttpRetry.shouldRetryResponseCode(outcome.code) -> HostThrottle.Outcome.RetryableError
            else -> HostThrottle.Outcome.Success
        }
        is HtmlFetchOutcome.NetworkError -> HostThrottle.Outcome.NetworkError
    }

    private suspend fun downloadHtml(url: String, priority: ImageRequestPriority = ImageRequestPriority.USER_REQUESTED): String {
        val useShortTimeout = priority == ImageRequestPriority.SPECULATIVE
        val attempts = if (useShortTimeout) SHORT_REQUEST_ATTEMPTS else USER_REQUEST_ATTEMPTS
        val client = if (useShortTimeout) shortTimeoutClient else userHtmlClient

        // Novelight chapter pages are "Loading…" shells; the prose lives behind the read-chapter
        // XHR endpoint. Fetch that instead and unwrap its JSON into clean chapter HTML so the
        // cached/parsed content (read, prefetch, offline) is the actual text. See NovelightUrls.
        val novelightChapterId = NovelightUrls.chapterId(url)
        val fetchUrl = novelightChapterId?.let { NovelightUrls.readChapterUrl(it) } ?: url

        var lastException: Exception? = null

        repeat(attempts) { attempt ->
            val outcome = runCatching {
                hostThrottle.execute(
                    url = fetchUrl,
                    block = { fetchHtmlOnce(client, fetchUrl) },
                    classify = ::classifyHtmlOutcome
                )
            }.getOrElse { t -> HtmlFetchOutcome.NetworkError(t as? Exception ?: Exception(t)) }

            when (outcome) {
                is HtmlFetchOutcome.Body -> return finalizeHtmlBody(outcome.text, novelightChapterId)
                is HtmlFetchOutcome.HttpError -> {
                    val e = Exception("HTTP ${outcome.code}")
                    lastException = e
                    if (!HttpRetry.shouldRetryResponseCode(outcome.code) || attempt == attempts - 1) throw e
                    delay(HttpRetry.nextRetryDelayMs(outcome.retryAfterMs, url, attempt))
                }
                is HtmlFetchOutcome.NetworkError -> {
                    lastException = outcome.cause
                    if (attempt == attempts - 1) throw outcome.cause
                    delay(HttpRetry.nextRetryDelayMs(null, url, attempt))
                }
            }
        }
        throw lastException ?: Exception("Failed to download HTML")
    }

    // For a Novelight chapter the fetched body is read-chapter JSON: unwrap it into clean prose
    // HTML (or an empty doc when gated/premium). Everything else is returned verbatim.
    private fun finalizeHtmlBody(body: String, novelightChapterId: String?): String =
        if (novelightChapterId != null) {
            NovelightUrls.extractChapterContentHtml(body) ?: EMPTY_HTML_DOCUMENT
        } else {
            body
        }

    private fun fetchHtmlOnce(client: OkHttpClient, url: String): HtmlFetchOutcome {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", getReferer(url))
            .apply {
                // Novelight's XHR endpoints (read-chapter, etc.) 403 without this header.
                if (NovelightUrls.requiresXhrHeader(url)) addHeader("X-Requested-With", "XMLHttpRequest")
            }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use HtmlFetchOutcome.HttpError(
                        code = response.code,
                        retryAfterMs = HttpRetry.parseRetryAfterMs(response.header("Retry-After"))
                    )
                }
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_HTML_BODY_BYTES) {
                    return@use HtmlFetchOutcome.NetworkError(
                        IOException("HTML body too large: $contentLength bytes")
                    )
                }
                body.source().use { source ->
                    val buffer = okio.Buffer()
                    val readLimit = MAX_HTML_BODY_BYTES + 1
                    var totalRead = 0L
                    while (totalRead < readLimit) {
                        val read = source.read(buffer, readLimit - totalRead)
                        if (read == -1L) break
                        totalRead += read
                    }
                    if (totalRead > MAX_HTML_BODY_BYTES) {
                        HtmlFetchOutcome.NetworkError(
                            IOException("HTML body exceeded $MAX_HTML_BODY_BYTES bytes")
                        )
                    } else {
                        HtmlFetchOutcome.Body(buffer.readUtf8())
                    }
                }
            }
        } catch (e: IOException) {
            HtmlFetchOutcome.NetworkError(e)
        }
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
        val isLongStrip = WebChapterElementShaper.isLongStripContent(url = url, elements = elements)
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
        val groupedElements = WebChapterElementShaper.groupSimilarElements(dimensionedElements)

        return WebChapterElementShaper.expandWideElements(groupedElements, url)
    }


    private suspend fun enrichImageDimensions(
        imageElements: List<ContentElement.Image>,
        pageUrl: String,
        diskOnly: Boolean
    ): List<ContentElement.Image> = withContext(Dispatchers.IO) {
        val needsLookup = imageElements
            .filter { it.width <= 0 || it.height <= 0 }
            .map { it.url }
        val cached = imageDimensionCache.getMany(needsLookup)

        imageElements.map { img ->
            if (img.width > 0 && img.height > 0) return@map async { img }
            val hit = cached[img.url]
            if (hit != null) {
                return@map async { img.copy(width = hit.width, height = hit.height) }
            }
            async {
                DIMENSION_SEMAPHORE.withPermit {
                    fetchImageDimensions(img.url, pageUrl, diskOnly = diskOnly)?.let { (w, h) ->
                        imageDimensionCache.persist(img.url, w, h)
                        img.copy(width = w, height = h)
                    } ?: img
                }
            }
        }.awaitAll()
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

    private suspend fun executePrefetch(
        url: String,
        mode: PrefetchMode,
        onProgress: (suspend (PrefetchResult) -> Unit)? = null
    ): PrefetchResult {
        val priority = if (mode == PrefetchMode.SPECULATIVE) ImageRequestPriority.SPECULATIVE else ImageRequestPriority.USER_REQUESTED
        val safeUrl = UrlSanitizer.sanitize(url)
        val cachedDocument = try {
            getDocumentFromCacheOrNetwork(
                url = url,
                priority = priority,
                writeTier = StorageTier.CACHE
            )
        } catch (e: Exception) {
            val inspected = if (mode == PrefetchMode.USER_REQUESTED) {
                offlineChapterStore.inspect(url)
            } else {
                inspectCacheInternal(url = url)
            }
            return inspected.copy(isRetryable = shouldRetryException(e))
        }

        if (mode == PrefetchMode.USER_REQUESTED) {
            val result = offlineChapterStore.downloadChapter(
                url = url,
                document = cachedDocument.document,
                onProgress = onProgress
            )
            Log.d(
                TAG,
                "offline download final url=$safeUrl complete=${result.isComplete} " +
                    "cached=${result.cachedImages}/${result.totalImages}"
            )
            return result.copy(isInProgress = false)
        }

        Log.d(TAG, "SPECULATIVE prefetch HTML-only url=$safeUrl")
        val finalResult = inspectCacheInternal(url, cachedDocument.document).copy(isInProgress = false)
        Log.d(
            TAG,
            "prefetch final result url=$safeUrl complete=${finalResult.isComplete} " +
                "cached=${finalResult.cachedImages}/${finalResult.totalImages} " +
                "permanentFailures=${finalResult.hasPermanentFailures}"
        )
        return finalResult
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

        val cachedFile = imageCache.destinationFile(imageUrl, StorageTier.CACHE)
        // Unique per attempt: a SPECULATIVE download that gets cancelled mid-write by an
        // arriving USER_REQUESTED would otherwise race on the same `.tmp` path and
        // interleave bytes. Random suffix isolates the two writers so the new attempt
        // never observes partial data from the cancelled one.
        val tempFile = File(
            cachedFile.parent,
            "${cachedFile.name}.${java.util.UUID.randomUUID()}.tmp"
        )

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
                                val finalFile = when {
                                    tempFile.renameTo(cachedFile) -> cachedFile
                                    // Fallback: rename failed but a file already sits at the
                                    // target path. Only trust it if it actually decodes; a stale
                                    // truncated/HTML file from a pre-fix download would otherwise
                                    // get cemented in place and the fresh tempFile thrown away.
                                    cachedFile.exists() && imageCache.isValidImageFile(cachedFile) -> {
                                        tempFile.delete()
                                        cachedFile
                                    }
                                    else -> {
                                        // Either nothing at target, or what's there is corrupt.
                                        // Force the freshly-downloaded tempFile into place.
                                        cachedFile.delete()
                                        if (tempFile.renameTo(cachedFile)) cachedFile else null
                                    }
                                }
                                if (finalFile == null) {
                                    tempFile.delete()
                                    ImageDownloadResult.Failure(false)
                                } else if (!imageCache.isValidImageFile(finalFile)) {
                                    // Server returned non-image bytes (HTML challenge, truncated
                                    // payload). Treat as retryable so the next pass tries again
                                    // and only escalates to a permanent failure on real HTTP 4xx.
                                    Log.d(TAG, "Invalid image payload, deleting and retrying: ${UrlSanitizer.sanitize(imageUrl)}")
                                    finalFile.delete()
                                    ImageDownloadResult.Failure(true)
                                } else {
                                    ImageDownloadResult.Success(finalFile)
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
                        // Invalidate here — when the download attempt itself finishes — not in
                        // the awaiter: the deferred is shared and outlives a cancelled awaiter
                        // (Coil disposing a request mid-download), and an awaiter-side
                        // invalidation would fire before the file lands, letting a probe
                        // re-memoize "missing" that nothing ever corrects.
                        imageCache.invalidateMediaState(imageUrl)
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
        cachedDocument: Document? = null
    ): PrefetchResult {
        val htmlFile = findExistingCachedFile(url)
        val htmlCached = htmlFile != null

        val memo = resolveParsedImageMemo(url, htmlFile, cachedDocument)

        val imageUrls = memo?.imageUrls.orEmpty()
        val downloadedCount = imageUrls.count { imageUrl ->
            imageCache.findExistingCachedMediaFile(imageUrl) != null
        }

        val isInProgress = chapterPrefetchMutex.withLock { url in inFlightChapterPrefetches }
        val finalComplete = when {
            !htmlCached -> false
            imageUrls.isNotEmpty() -> downloadedCount == imageUrls.size
            memo?.hasImageTags == true -> false
            // The page carries manga-reader CSS hooks but the parser found zero images —
            // typically a JS-rendered chapter where static HTML is just the shell. Refuse
            // to claim Downloaded; the user would otherwise see a 1-second tap that ends
            // with a chapter that has nothing to render offline.
            memo?.hasMangaReaderHints == true -> false
            // A chapter URL with zero parseable images AND zero raw img tags is a JS-rendered
            // page (Next.js, SPA) where the static HTML carries only the shell. The
            // bodyNonEmpty heuristic that follows is correct for novel text pages but would
            // falsely mark these as Downloaded — refuse to claim complete for chapter URLs
            // until we actually see images.
            isChapterPageUrl(url) -> false
            else -> memo?.bodyNonEmpty == true
        }

        return PrefetchResult(
            url = url,
            htmlCached = htmlCached,
            totalImages = imageUrls.size,
            cachedImages = downloadedCount,
            isComplete = finalComplete,
            isInProgress = isInProgress,
            isRetryable = !finalComplete,
            isPersistentDownload = false,
            hasPermanentFailures = false
        )
    }

    private fun isChapterPageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/chapter/") ||
            lower.contains("/chapter-") ||
            lower.contains("-chapter-") ||
            (lower.contains("/manga/") && lower.contains("chapter"))
    }

    private fun resolveParsedImageMemo(
        url: String,
        htmlFile: File?,
        cachedDocument: Document?
    ): ParsedImageMemo? {
        val memo = when {
            cachedDocument != null -> computeParsedImageMemo(cachedDocument, url, htmlFile)
            htmlFile == null -> null
            else -> reuseOrReparseMemo(url, htmlFile)
        }
        if (memo != null && htmlFile != null) parsedImageMemo[url] = memo
        return memo
    }

    private fun reuseOrReparseMemo(url: String, htmlFile: File): ParsedImageMemo? {
        val cached = parsedImageMemo[url]
        val cacheValid = cached != null &&
            cached.mtime == htmlFile.lastModified() &&
            cached.length == htmlFile.length()
        if (cacheValid) return cached
        return runCatching { Jsoup.parse(htmlFile, "UTF-8", url) }
            .getOrNull()
            ?.let { computeParsedImageMemo(it, url, htmlFile) }
    }

    private fun computeParsedImageMemo(document: Document, url: String, htmlFile: File?): ParsedImageMemo {
        val urls = runCatching {
            extractImageUrls(htmlParser.parse(document, url))
                .distinct()
                .filter { it.startsWith("http") }
        }.getOrDefault(emptyList())
        val hasImageTags = document.select("img[src], image[href], image[xlink|href], source[srcset]").isNotEmpty()
        val bodyNonEmpty = document.body().html().isNotBlank()
        val hasMangaReaderHints = detectMangaReaderHints(document)
        return ParsedImageMemo(
            mtime = htmlFile?.lastModified() ?: 0L,
            length = htmlFile?.length() ?: 0L,
            imageUrls = urls,
            hasImageTags = hasImageTags,
            bodyNonEmpty = bodyNonEmpty,
            hasMangaReaderHints = hasMangaReaderHints
        )
    }

    // A chapter page intends to show manga even if the static HTML hasn't rendered the
    // page list yet (JS-rendered). Presence of any of these selectors is a strong signal
    // the page should not be treated as a "novel-style text page" for completeness.
    private fun detectMangaReaderHints(document: Document): Boolean {
        return ChapterDocumentClassifier.detectMangaReaderHints(document)
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
        cacheFileVariants(url).forEach { variant ->
            parsedContentCache.delete(variant)
            variant.delete()
        }
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
}
