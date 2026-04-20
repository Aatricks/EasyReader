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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import io.aatricks.novelscraper.di.HtmlCacheDir
import io.aatricks.novelscraper.di.MediaCacheDir
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .callTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NON_ESSENTIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val imageDownloadMutex = Mutex()
    private val chapterPrefetchMutex = Mutex()
    private val inFlightImageDownloads = mutableMapOf<String, Deferred<File?>>()
    private val inFlightChapterPrefetches = mutableMapOf<String, Deferred<PrefetchResult>>()
    private val inFlightChapterPrefetchModes = mutableMapOf<String, PrefetchMode>()

    private data class CachedDocument(
        val document: Document,
        val fromCache: Boolean
    )

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

        val deferred = imageDownloadMutex.withLock {
            inFlightImageDownloads[imageUrl] ?: repositoryScope.async {
                runCatching {
                    val request = Request.Builder()
                        .url(imageUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Referer", getReferer(pageUrl))
                        .build()

                    val client = if (useShortTimeout) shortTimeoutClient else okHttpClient
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        response.body?.let { body ->
                            val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")
                            try {
                                tempFile.writeBytes(body.bytes())
                                if (tempFile.renameTo(cachedFile)) {
                                    cachedFile
                                } else if (cachedFile.exists()) {
                                    tempFile.delete()
                                    cachedFile
                                } else {
                                    tempFile.delete()
                                    null
                                }
                            } catch (e: Exception) {
                                tempFile.delete()
                                throw e
                            }
                        }
                    }
                }.getOrNull()
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
            val uri = java.net.URI(url)
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

            val req = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", getReferer(pageUrl))
                .addHeader("Range", "bytes=0-16383")
                .build()

            shortTimeoutClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return@use null
                    ImageBoundsParser.parse(bytes)
                } else null
            }
        }.getOrNull() ?: downloadAndCacheImage(
            imageUrl = imageUrl,
            pageUrl = pageUrl,
            useShortTimeout = true
        )?.let { ImageBoundsParser.parse(it) }
    }

    private suspend fun executePrefetch(url: String, mode: PrefetchMode): PrefetchResult {
        val cachedDocument = getDocumentFromCacheOrNetwork(
            url = url,
            useShortTimeout = mode == PrefetchMode.SPECULATIVE
        )
        val imageUrls = extractImageUrls(htmlParser.parse(cachedDocument.document, url))
            .distinct()
            .filter { it.startsWith("http") }

        val missingImages = imageUrls.filterNot { getCachedMediaFile(it).exists() }
        val requestedImages = when (mode) {
            PrefetchMode.USER_REQUESTED -> missingImages
            PrefetchMode.SPECULATIVE -> missingImages.take(MAX_SPECULATIVE_IMAGES)
        }

        when (mode) {
            PrefetchMode.USER_REQUESTED -> cacheImages(
                imageUrls = requestedImages,
                pageUrl = url,
                maxImages = requestedImages.size,
                useShortTimeout = false,
                maxConcurrency = MAX_CONCURRENT_DOWNLOADS
            )

            PrefetchMode.SPECULATIVE -> cacheImages(
                imageUrls = requestedImages,
                pageUrl = url,
                maxImages = requestedImages.size,
                useShortTimeout = true,
                maxConcurrency = 1
            )
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
