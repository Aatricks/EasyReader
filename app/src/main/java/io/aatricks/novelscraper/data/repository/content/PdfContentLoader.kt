package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class PdfContentLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d+$")
        private const val MAX_LOCAL_CACHE_SIZE = 100
        private const val MAX_GLOBAL_PDF_CACHE_SIZE = 5
        private const val MAX_GLOBAL_PAGE_CACHE_PER_PDF = 50
        private const val PREFETCH_FORWARD = 3
        private const val PREFETCH_BACKWARD = 1
        private const val EVICTION_DISTANCE = 30
        private const val ESTIMATED_PAGE_HEIGHT_DP = 1200
        private const val IMAGE_DOWNSAMPLE_THRESHOLD = 2048
    }

    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val pageCountCache = ConcurrentHashMap<String, Int>()
    private val globalContentCache = LruCache<String, MutableMap<Int, ContentElement>>(MAX_GLOBAL_PDF_CACHE_SIZE)

    suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            var estimatedHeight = ESTIMATED_PAGE_HEIGHT_DP
            
            val pageCount = openPdfDocument(filePath)?.use { docHandle ->
                val doc = docHandle.document
                val count = doc.numberOfPages
                if (count > 0) {
                    try {
                        val firstPage = doc.getPage(1)
                        val pageSize = firstPage.pageSize
                        // 1 point = 1/72 inch, 1 DP = 1/160 inch. 
                        // DP = points * 160 / 72 = points * 2.222
                        estimatedHeight = (pageSize.height * 2.222f).toInt().coerceIn(400, 3000)
                    } catch (_: Exception) {}
                }
                count
            } ?: throw Exception("PDF not found")
            
            pageCountCache[filePath] = pageCount

            if (pageCount == 0) throw Exception("No text in PDF")

            val title = if (filePath.startsWith("content://")) {
                Uri.parse(filePath).lastPathSegment ?: "PDF"
            } else {
                File(filePath).nameWithoutExtension
            }

            ContentResult.Success(
                elements = PdfLazyList(filePath, pageCount, estimatedHeight),
                title = title,
                url = filePath,
                textCount = pageCount,
                imageCount = 0
            )
        } catch (e: Exception) {
            ContentResult.Error("PDF Error: ${e.message}")
        }
    }

    private fun openPdfDocument(filePath: String): PdfDocumentHandle? {
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

    private class PdfDocumentHandle(
        val document: PdfDocument,
        val pfd: ParcelFileDescriptor?
    ) : java.io.Closeable {
        val numberOfPages: Int get() = document.numberOfPages

        override fun close() {
            try { document.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        fun use(block: (PdfDocumentHandle) -> Int): Int {
            return try {
                block(this)
            } finally {
                close()
            }
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

    private inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int,
        private val estimatedHeight: Int
    ) : AbstractList<ContentElement>(), java.io.Closeable {
        
        private val pageCache = mutableStateMapOf<Int, ContentElement>().apply {
            globalContentCache.get(filePath)?.let { putAll(it) }
        }
        
        private val loadingJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
        
        // Handle pool for parallel loading
        private val poolSize = 3
        private val handlePool = ConcurrentHashMap.newKeySet<PdfDocumentHandle>()
        private val poolMutex = Mutex()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            
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
            if (loadingJobs.containsKey(index)) return

            val job = loaderScope.launch {
                val result = loadPageContent(index + 1)
                
                // Process images outside the mutex (result contains raw image data)
                val processedElements = withContext(Dispatchers.Default) {
                    processExtractedElements(result)
                }

                withContext(Dispatchers.Main) {
                    addToCache(index, ContentElement.PageContent(processedElements))
                    loadingJobs.remove(index)
                }
            }
            
            loadingJobs[index] = job

            if (loadingJobs.size > 15) {
                val furthestKey = loadingJobs.keys.maxByOrNull { abs(it - index) }
                if (furthestKey != null && abs(furthestKey - index) > 10) {
                    loadingJobs.remove(furthestKey)?.cancel()
                }
            }
        }

        private fun addToCache(index: Int, content: ContentElement) {
            pageCache[index] = content
            
            synchronized(globalContentCache) {
                var docCache = globalContentCache.get(filePath)
                if (docCache == null) {
                    docCache = mutableMapOf()
                    globalContentCache.put(filePath, docCache)
                }
                docCache[index] = content
                
                if (docCache.size > MAX_GLOBAL_PAGE_CACHE_PER_PDF) {
                    val firstKey = docCache.keys.firstOrNull()
                    if (firstKey != null) docCache.remove(firstKey)
                }
            }

            // Distance-based eviction: remove pages far from current position
            if (pageCache.size > MAX_LOCAL_CACHE_SIZE) {
                val toEvict = pageCache.keys.filter { abs(it - index) > EVICTION_DISTANCE }
                toEvict.forEach { pageCache.remove(it) }
            }
        }

        private suspend fun acquireHandle(): PdfDocumentHandle? {
            poolMutex.withLock {
                val handle = handlePool.find { !it.document.isClosed }
                if (handle != null) {
                    handlePool.remove(handle)
                    return handle
                }
                if (handlePool.size < poolSize) {
                    return openPdfDocument(filePath)
                }
                // If pool is full, wait and retry or just return null (should not happen with mutex)
            }
            // Fallback for full pool: wait a bit
            for (i in 1..5) {
                delay(50 * i.toLong())
                poolMutex.withLock {
                    val handle = handlePool.find { !it.document.isClosed }
                    if (handle != null) {
                        handlePool.remove(handle)
                        return handle
                    }
                }
            }
            return openPdfDocument(filePath) // Last resort
        }

        private fun releaseHandle(handle: PdfDocumentHandle?) {
            if (handle == null) return
            if (handle.document.isClosed) return
            
            val added = handlePool.add(handle)
            if (!added || handlePool.size > poolSize) {
                handlePool.remove(handle)
                handle.close()
            }
        }

        private suspend fun loadPageContent(pageNum: Int): ExtractedResult {
            val handle = acquireHandle() ?: return ExtractedResult(emptyList(), "")
            
            return try {
                val doc = handle.document
                if (pageNum > doc.numberOfPages) return ExtractedResult(emptyList(), "")

                val page = doc.getPage(pageNum)
                val strategy = CombinedExtractionStrategy()
                val processor = PdfCanvasProcessor(strategy)
                
                // Parsing content is the part that needs the document handle
                processor.processPageContent(page)
                
                ExtractedResult(
                    rawImages = strategy.getRawImages(),
                    rawText = strategy.resultantText ?: ""
                )
            } catch (e: Exception) {
                ExtractedResult(emptyList(), "Error loading page $pageNum: ${e.message}")
            } finally {
                releaseHandle(handle)
            }
        }

        private suspend fun processExtractedElements(extracted: ExtractedResult): List<ContentElement> {
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

            val processedImages = extracted.rawImages.mapNotNull { raw ->
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = calculateInSampleSize(raw.width, raw.height, IMAGE_DOWNSAMPLE_THRESHOLD) }
                    val bitmap = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, options) ?: return@mapNotNull null
                    
                    val fileName = "pdf_img_${UUID.randomUUID()}.webp"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    }
                    bitmap.recycle()
                    
                    ContentElement.Image(
                        url = "file://${file.absolutePath}",
                        width = raw.width,
                        height = raw.height
                    )
                } catch (_: Exception) { null }
            }

            if (paragraphs.isEmpty() && processedImages.isEmpty() && rawText.isNotBlank()) {
                paragraphs.add(ContentElement.Text(rawText.trim()))
            }

            return paragraphs + processedImages
        }

        override fun close() {
            handlePool.forEach { it.close() }
            handlePool.clear()
        }
    }

    private data class ExtractedResult(
        val rawImages: List<RawImage>,
        val rawText: String
    )

    private data class RawImage(
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
                } catch (_: Exception) {}
            }
        }

        fun getRawImages(): List<RawImage> = rawImages
    }
}
