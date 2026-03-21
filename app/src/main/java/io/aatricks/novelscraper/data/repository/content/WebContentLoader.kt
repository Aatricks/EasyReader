package io.aatricks.novelscraper.data.repository.content

import android.graphics.BitmapFactory
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.repository.HtmlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
        private const val SKIP_DIMENSION_CHECK_THRESHOLD = 50
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun loadWebContent(url: String): ContentResult = withContext(Dispatchers.IO) {
        val document = getDocumentFromCacheOrNetwork(url)

        val elements = htmlParser.parse(document, url)
        val finalElements = processChapterElements(elements, url)

        backgroundCacheImages(finalElements, url)
        
        ContentResult.Success(
            elements = finalElements,
            title = document.title().takeIf { it.isNotBlank() },
            url = url
        )
    }

    suspend fun prefetch(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val html = downloadHtml(url)
            getCachedFile(url).writeText(html)
            val doc = Jsoup.parse(html, url)

            extractImageUrls(htmlParser.parse(doc, url)).forEach { imageUrl ->
                    repositoryScope.launch {
                        runCatching { downloadAndCacheImage(imageUrl, url) }
                    }
            }
            true
        }.getOrDefault(false)
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
        if (!imageUrl.startsWith("http")) return@withContext null
        
        runCatching {
            val cachedFile = getCachedMediaFile(imageUrl)
            if (cachedFile.exists()) return@runCatching cachedFile

            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", getReferer(pageUrl))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.let { body ->
                    val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")
                    try {
                        tempFile.writeBytes(body.bytes())
                        if (tempFile.renameTo(cachedFile)) {
                            cachedFile
                        } else {
                            // If rename fails, check if the file was created by another process
                            if (cachedFile.exists()) {
                                tempFile.delete()
                                cachedFile
                            } else {
                                tempFile.delete()
                                null
                            }
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        throw e
                    }
                }
            }
        }.getOrNull()
    }

    fun getCachedMediaFile(url: String): File = File(mediaCacheDir, url.hashCode().toString())

    fun getCachedFile(url: String): File = File(cacheDir, "${url.hashCode()}.html")

    fun isCached(url: String): Boolean = getCachedFile(url).exists()

    fun clearCache(url: String) {
        getCachedFile(url).delete()
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

    private fun getDocumentFromCacheOrNetwork(url: String): Document {
        val cachedFile = getCachedFile(url)
        return if (cachedFile.exists()) {
            Jsoup.parse(cachedFile, "UTF-8", url)
        } else {
            val html = downloadHtml(url)
            cachedFile.writeText(html)
            Jsoup.parse(html, url)
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

    private fun backgroundCacheImages(elements: List<ContentElement>, pageUrl: String): Unit {
        repositoryScope.launch {
            val imageChannel = Channel<String>(Channel.UNLIMITED)

            repeat(MAX_CONCURRENT_DOWNLOADS) {
                launch {
                    for (url in imageChannel) {
                        downloadAndCacheImage(url, pageUrl)
                    }
                }
            }

            elements.forEach { element ->
                when (element) {
                    is ContentElement.Image -> imageChannel.trySend(element.url)
                    is ContentElement.ImageGroup -> element.images.forEach { img ->
                        imageChannel.trySend(img.url)
                    }
                    else -> {}
                }
            }
            imageChannel.close()
        }
    }

    private fun downloadHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", getReferer(url))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
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

    private suspend fun processChapterElements(elements: List<ContentElement>, url: String): List<ContentElement> {
        val imageElements = elements.flatMap { element ->
            when (element) {
                is ContentElement.Image -> listOf(element)
                is ContentElement.ImageGroup -> element.images
                else -> emptyList()
            }
        }

        if (imageElements.isEmpty()) return elements

        val imagesWithDims = enrichImageDimensions(imageElements, url)

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
        pageUrl: String
    ): List<ContentElement.Image> = withContext(Dispatchers.IO) {
        imageElements.map { img ->
            async {
                DIMENSION_SEMAPHORE.withPermit {
                    fetchImageDimensions(img.url, pageUrl, diskOnly = false)?.let { (w, h) ->
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
            if (cached.exists()) {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, opt)
                if (opt.outWidth > 0) return@runCatching Pair(opt.outWidth, opt.outHeight)
            }

            // If disk-only mode (manhwa, large chapters), don't perform any network request.
            if (diskOnly) return@withContext null

            val req = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", getReferer(pageUrl))
                .addHeader("Range", "bytes=0-16383")
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(response.body?.byteStream(), null, opt)
                    if (opt.outWidth > 0) Pair(opt.outWidth, opt.outHeight) else null
                } else null
            }
        }.getOrNull()
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
        // Group if at least one image is shorter than a normal manga page (h/w < 1.2) and
        // combined they form a continuous result (total h/w < 8.0).
        // Larger limit (8.0) handles long-strip manhwa composed of many small strips.
        return (currentRatio < 1.2f || lastRatio < 1.2f) && lastRatio + currentRatio < 8.0f
    }

    private fun shouldGroupWithLastGroup(last: ContentElement, current: ContentElement.Image): Boolean {
        if (last !is ContentElement.ImageGroup) return false
        if (current.side != ContentElement.Image.Side.FULL) return false
        val lastInGroup = last.images.last()
        if (lastInGroup.width <= 0 || current.width <= 0) return false
        if (kotlin.math.abs(lastInGroup.width - current.width).toFloat() / lastInGroup.width > 0.05f) return false
        val groupRatio = last.images.sumOf { it.height }.toFloat() / lastInGroup.width
        val currentRatio = current.height.toFloat() / current.width
        // Allow grouping up to 8.0 ratio (about 4 screen heights) to maintain continuity
        // while still allowing some LazyColumn recycling for extremely long chapters.
        return currentRatio < 1.2f && groupRatio + currentRatio < 8.0f
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
}
