package io.aatricks.easyreader.data.repository.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PdfContentLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opener: PdfDocumentOpener
) {
    data class LoadingProfile(
        val prefetchForward: Int,
        val prefetchBackward: Int,
        val maxInFlightJobs: Int
    )

    companion object {
        private const val TAG = "PdfContentLoader"
        private val PAGE_NUMBER_REGEX = Regex("^\\d+$")
        private const val MAX_LOCAL_CACHE_SIZE = 100
        private const val MAX_GLOBAL_PDF_CACHE_SIZE = 5
        private const val MAX_GLOBAL_PAGE_CACHE_PER_PDF = 50
        private const val PREFETCH_FORWARD = 3
        private const val PREFETCH_BACKWARD = 0
        private const val MAX_IN_FLIGHT_JOBS = 6
        private const val MAX_JOB_DISTANCE = 6
        private const val EVICTION_DISTANCE = 30
        private const val ESTIMATED_PAGE_HEIGHT_DP = 1200
        private const val IMAGE_DOWNSAMPLE_THRESHOLD = 2048
    }

    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val pageCountCache = ConcurrentHashMap<String, Int>()
    private val globalContentCache = LruCache<String, LinkedHashMap<Int, ContentElement>>(MAX_GLOBAL_PDF_CACHE_SIZE)

    internal fun loadingProfileForTests(): LoadingProfile = LoadingProfile(
        prefetchForward = PREFETCH_FORWARD,
        prefetchBackward = PREFETCH_BACKWARD,
        maxInFlightJobs = MAX_IN_FLIGHT_JOBS
    )

    suspend fun loadPdfContent(filePath: String, preloadPageIndex: Int? = null): ContentResult = withContext(Dispatchers.IO) {
        try {
            var estimatedHeight = ESTIMATED_PAGE_HEIGHT_DP
            
            val pageCount = opener.open(filePath)?.use { docHandle ->
                val doc = docHandle.document
                val count = doc.numberOfPages
                if (count > 0) {
                    try {
                        val firstPage = doc.getPage(1)
                        val pageSize = firstPage.pageSize
                        // 1 point = 1/72 inch, 1 DP = 1/160 inch. 
                        // DP = points * 160 / 72 = points * 2.222
                        estimatedHeight = (pageSize.height * 2.222f).toInt().coerceIn(400, 3000)
                    } catch (e: Exception) {
                        Log.w(TAG, "page size sniff failed", e)
                    }
                }
                count
            } ?: throw Exception("PDF not found")
            
            pageCountCache[filePath] = pageCount

            if (pageCount == 0) throw Exception("No text in PDF")

            val preloadedPages = preloadPageContent(filePath, pageCount, preloadPageIndex)

            val title = if (filePath.startsWith("content://")) {
                Uri.parse(filePath).lastPathSegment ?: "PDF"
            } else {
                File(filePath).nameWithoutExtension
            }

            ContentResult.Success(
                elements = PdfLazyList(filePath, pageCount, estimatedHeight, preloadedPages),
                title = title,
                url = filePath,
                textCount = pageCount,
                imageCount = 0
            )
        } catch (e: Exception) {
            ContentResult.Error("PDF Error: ${e.message}")
        }
    }

    private fun getGlobalCacheSnapshot(filePath: String): Map<Int, ContentElement> = synchronized(globalContentCache) {
        globalContentCache.get(filePath)?.toMap().orEmpty()
    }

    private fun getGlobalCachedPage(filePath: String, index: Int): ContentElement? = synchronized(globalContentCache) {
        globalContentCache.get(filePath)?.get(index)
    }

    private fun addToGlobalCache(filePath: String, index: Int, content: ContentElement) {
        synchronized(globalContentCache) {
            var docCache = globalContentCache.get(filePath)
            if (docCache == null) {
                docCache = LinkedHashMap<Int, ContentElement>(MAX_GLOBAL_PAGE_CACHE_PER_PDF + 1, 0.75f, true)
                globalContentCache.put(filePath, docCache)
            }
            docCache[index] = content

            if (docCache.size > MAX_GLOBAL_PAGE_CACHE_PER_PDF) {
                val eldestKey = docCache.entries.firstOrNull()?.key
                if (eldestKey != null) docCache.remove(eldestKey)
            }
        }
    }

    private suspend fun preloadPageContent(
        filePath: String,
        pageCount: Int,
        preloadPageIndex: Int?
    ): Map<Int, ContentElement> {
        if (preloadPageIndex == null || pageCount <= 0) return emptyMap()

        val targetIndex = preloadPageIndex.coerceIn(0, pageCount - 1)
        val cachedPage = getGlobalCachedPage(filePath, targetIndex)
        if (cachedPage != null) {
            return mapOf(targetIndex to cachedPage)
        }

        val handle = opener.open(filePath) ?: return emptyMap()
        return try {
            val pageContent = loadPageElement(handle, filePath, targetIndex + 1)
            addToGlobalCache(filePath, targetIndex, pageContent)
            mapOf(targetIndex to pageContent)
        } catch (e: Exception) {
            Log.w(TAG, "pdf page load failed at $targetIndex", e)
            emptyMap()
        } finally {
            handle.close()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        val larger = maxOf(width, height)
        if (larger > maxDim) {
            while (larger / sampleSize > maxDim) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private suspend fun extractPageContent(handle: PdfDocumentHandle, pageNum: Int): ExtractedResult {
        return try {
            val doc = handle.document
            if (pageNum !in 1..doc.numberOfPages) {
                return ExtractedResult(emptyList(), "")
            }

            val page = doc.getPage(pageNum)
            val strategy = CombinedExtractionStrategy()
            val processor = PdfCanvasProcessor(strategy)
            processor.processPageContent(page)

            ExtractedResult(
                rawImages = strategy.getRawImages(),
                rawText = strategy.resultantText ?: ""
            )
        } catch (e: Exception) {
            ExtractedResult(emptyList(), "Error loading page $pageNum: ${e.message}")
        }
    }

    private suspend fun loadPageElement(handle: PdfDocumentHandle, filePath: String, pageNum: Int): ContentElement {
        val extracted = extractPageContent(handle, pageNum)
        val processedElements = withContext(Dispatchers.Default) {
            processExtractedElements(filePath, pageNum, extracted)
        }
        return ContentElement.PageContent(processedElements)
    }

    internal fun processExtractedElements(filePath: String, pageNum: Int, extracted: ExtractedResult): List<ContentElement> {
        val paragraphs = mutableListOf<ContentElement>()
        val rawText = extracted.rawText

        if (rawText.startsWith("Error loading page")) {
            return listOf(ContentElement.Text(rawText))
        }

        val sb = java.lang.StringBuilder()
        for (line in rawText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (sb.isNotEmpty()) {
                    paragraphs.add(ContentElement.Text(sb.toString()))
                    sb.setLength(0)
                }
            } else {
                if (trimmed.matches(PAGE_NUMBER_REGEX)) continue
                if (sb.isNotEmpty()) {
                    if (sb.endsWith("-")) {
                        sb.setLength(sb.length - 1)
                    } else {
                        sb.append(" ")
                    }
                }
                sb.append(trimmed)
            }
        }
        if (sb.isNotEmpty()) {
            paragraphs.add(ContentElement.Text(sb.toString()))
        }

        val docKey = CacheKeyUtils.keyFor(filePath)
        val imagesDir = File(context.cacheDir, "pdf_images/$docKey")

        val processedImages = extracted.rawImages.mapIndexedNotNull { imageIndex, raw ->
            try {
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val fileName = "page_${pageNum}_image_${imageIndex}.webp"
                val file = File(imagesDir, fileName)

                if (file.exists() && file.length() > 0) {
                    return@mapIndexedNotNull ContentElement.Image(
                        url = "file://${file.absolutePath}",
                        width = raw.width,
                        height = raw.height
                    )
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(raw.width, raw.height, IMAGE_DOWNSAMPLE_THRESHOLD)
                }
                val bitmap = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, options) ?: return@mapIndexedNotNull null

                val tmpFile = File(imagesDir, "$fileName.tmp")
                try {
                    FileOutputStream(tmpFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    }
                    if (tmpFile.renameTo(file)) {
                        ContentElement.Image(
                            url = "file://${file.absolutePath}",
                            width = raw.width,
                            height = raw.height
                        )
                    } else {
                        tmpFile.delete()
                        null
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    null
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "pdf image processing failed", e)
                null
            }
        }

        if (paragraphs.isEmpty() && processedImages.isEmpty() && rawText.isNotBlank()) {
            paragraphs.add(ContentElement.Text(rawText.trim()))
        }

        return paragraphs + processedImages
    }

    internal inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int,
        private val estimatedHeight: Int,
        preloadedPages: Map<Int, ContentElement> = emptyMap()
    ) : AbstractList<ContentElement>(), Closeable {
        
        private val pageCache = mutableStateMapOf<Int, ContentElement>().apply {
            putAll(getGlobalCacheSnapshot(filePath))
            putAll(preloadedPages)
        }
        
        private val loadingJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
        
        // Handle pool for parallel loading
        private val poolSize = 3
        private val handleSemaphore = Semaphore(poolSize)
        private val idleHandles = ArrayDeque<PdfDocumentHandle>()
        private var isClosed = false
        @Volatile private var lastAccessedIndex = preloadedPages.keys.firstOrNull() ?: 0

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            lastAccessedIndex = index

            pageCache[index]?.let { return it }

            triggerLoad(index)

            // Prefetch forward pages
            for (i in 1..PREFETCH_FORWARD) {
                val nextIndex = index + i
                if (nextIndex < size && !pageCache.containsKey(nextIndex)) {
                    triggerLoad(nextIndex)
                }
            }

            // Prefetch backward pages
            for (i in 1..PREFETCH_BACKWARD) {
                val prevIndex = index - i
                if (prevIndex >= 0 && !pageCache.containsKey(prevIndex)) {
                    triggerLoad(prevIndex)
                }
            }

            return ContentElement.Placeholder("Loading page ${index + 1}...", estimatedHeight)
        }

        private fun triggerLoad(index: Int) {
            if (isClosed || pageCache.containsKey(index) || loadingJobs.containsKey(index)) return

            val job = loaderScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = loadPageContent(index + 1)
                    addToCache(index, result)
                } finally {
                    loadingJobs.remove(index)
                }
            }

            val existing = loadingJobs.putIfAbsent(index, job)
            if (existing != null) {
                job.cancel()
                return
            }
            job.start()

            if (loadingJobs.size > MAX_IN_FLIGHT_JOBS) {
                val furthestCandidates = loadingJobs.keys
                    .sortedByDescending { abs(it - index) }

                furthestCandidates.forEach { candidate ->
                    if (loadingJobs.size <= MAX_IN_FLIGHT_JOBS) return@forEach
                    if (candidate == index) return@forEach

                    if (abs(candidate - index) > MAX_JOB_DISTANCE) {
                        loadingJobs.remove(candidate)?.cancel()
                    }
                }

                if (loadingJobs.size > MAX_IN_FLIGHT_JOBS) {
                    furthestCandidates.forEach { candidate ->
                        if (loadingJobs.size <= MAX_IN_FLIGHT_JOBS) return@forEach
                        if (candidate == index) return@forEach
                        loadingJobs.remove(candidate)?.cancel()
                    }
                }
            }
        }

        private fun addToCache(index: Int, content: ContentElement) {
            pageCache[index] = content
            addToGlobalCache(filePath, index, content)

            // Distance-based eviction: remove pages far from current position
            if (pageCache.size > MAX_LOCAL_CACHE_SIZE) {
                val anchorIndex = lastAccessedIndex
                val toEvict = pageCache.keys.filter { abs(it - anchorIndex) > EVICTION_DISTANCE }
                toEvict.forEach { pageCache.remove(it) }
            }
        }

        private suspend fun acquireHandle(): PdfDocumentHandle? {
            handleSemaphore.acquire()
            try {
                val handle = synchronized(idleHandles) {
                    if (isClosed) {
                        handleSemaphore.release()
                        return null
                    }
                    idleHandles.pollFirst()
                }

                if (handle == null) {
                    val newHandle = opener.open(filePath)
                    if (newHandle == null) {
                        handleSemaphore.release()
                    }
                    return newHandle
                }
                return handle
            } catch (e: Exception) {
                handleSemaphore.release()
                throw e
            }
        }

        private fun releaseHandle(handle: PdfDocumentHandle?) {
            if (handle == null) return

            val shouldClose = synchronized(idleHandles) {
                if (isClosed || handle.document.isClosed) {
                    true
                } else {
                    idleHandles.addLast(handle)
                    false
                }
            }

            if (shouldClose) {
                handle.close()
            }
            handleSemaphore.release()
        }

        private suspend fun loadPageContent(pageNum: Int): ContentElement {
            val handle = acquireHandle() ?: return ContentElement.PageContent(
                listOf(ContentElement.Text("Error loading page $pageNum: PDF not available"))
            )
            
            return try {
                loadPageElement(handle, filePath, pageNum)
            } finally {
                releaseHandle(handle)
            }
        }

        override fun close() {
            synchronized(idleHandles) {
                isClosed = true
                while (idleHandles.isNotEmpty()) {
                    idleHandles.pollFirst()?.close()
                }
                loadingJobs.values.forEach { it.cancel() }
                loadingJobs.clear()
            }
        }
    }

    internal data class ExtractedResult(
        val rawImages: List<RawImage>,
        val rawText: String
    )

    internal data class RawImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    private inner class CombinedExtractionStrategy : LocationTextExtractionStrategy() {
        private val rawImages = mutableListOf<RawImage>()

        override fun eventOccurred(data: IEventData?, type: EventType) {
            if (data == null) return
            super.eventOccurred(data, type)

            if (type == EventType.RENDER_IMAGE) {
                val renderInfo = data as? ImageRenderInfo ?: return
                try {
                    val imageObject = renderInfo.image ?: return
                    val imageBytes = imageObject.imageBytes ?: return
                    
                    // Directly extract dimensions from PDF image object dictionary
                    val width = imageObject.width.toInt()
                    val height = imageObject.height.toInt()
                    
                    if (width > 0 && height > 0) {
                        rawImages.add(RawImage(imageBytes, width, height))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pdf image extract event failed", e)
                }
            }
        }

        fun getRawImages(): List<RawImage> = rawImages
    }

    fun clearCache(url: String) {
        synchronized(globalContentCache) {
            globalContentCache.remove(url)
        }
        pageCountCache.remove(url)
        clearPdfImageCache(url)
    }

    private fun clearPdfImageCache(url: String) {
        val docKey = CacheKeyUtils.keyFor(url)
        val imagesDir = File(context.cacheDir, "pdf_images/$docKey")
        if (imagesDir.exists()) {
            imagesDir.deleteRecursively()
        }
    }

    fun clearAllCache() {
        synchronized(globalContentCache) {
            globalContentCache.evictAll()
        }
        pageCountCache.clear()
        clearAllPdfImageCache()
    }

    fun trimCache(maxBytes: Long) {
        FileSizeUtils.trimDirectoryToSize(File(context.cacheDir, "pdf_images"), maxBytes)
    }

    fun getCacheSize(): Long {
        return FileSizeUtils.calculateDirectorySize(File(context.cacheDir, "pdf_images"))
    }

    private fun clearAllPdfImageCache() {
        val imagesRoot = File(context.cacheDir, "pdf_images")
        if (imagesRoot.exists()) {
            imagesRoot.deleteRecursively()
        }
    }
}

class PdfDocumentHandle(
    val document: PdfDocument,
    val pfd: ParcelFileDescriptor?
) : java.io.Closeable {
    val numberOfPages: Int get() = document.numberOfPages

    override fun close() {
        runCatching { document.close() }.onFailure { android.util.Log.d("PdfDocumentHandle", "document close", it) }
        runCatching { pfd?.close() }.onFailure { android.util.Log.d("PdfDocumentHandle", "pfd close", it) }
    }

    fun use(block: (PdfDocumentHandle) -> Int): Int {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}

interface PdfDocumentOpener {
    fun open(filePath: String): PdfDocumentHandle?
}

@Singleton
class DefaultPdfDocumentOpener @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfDocumentOpener {
    override fun open(filePath: String): PdfDocumentHandle? {
        return if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val source = com.itextpdf.io.source.FileChannelRandomAccessSource(channel)
            val reader = PdfReader(source, ReaderProperties())
            PdfDocumentHandle(PdfDocument(reader), pfd)
        } else {
            val file = File(filePath)
            if (!file.exists()) return null
            PdfDocumentHandle(PdfDocument(PdfReader(filePath)), null)
        }
    }
}
