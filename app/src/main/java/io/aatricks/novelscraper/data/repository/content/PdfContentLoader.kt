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
            val cachedCount = pageCountCache[filePath]
            val pageCount = if (cachedCount != null) {
                cachedCount
            } else {
                val count = openPdfDocument(filePath)?.use { doc ->
                    doc.numberOfPages
                } ?: throw Exception("PDF not found")
                pageCountCache[filePath] = count
                count
            }

            if (pageCount == 0) throw Exception("No text in PDF")

            val title = if (filePath.startsWith("content://")) {
                Uri.parse(filePath).lastPathSegment ?: "PDF"
            } else {
                File(filePath).nameWithoutExtension
            }

            ContentResult.Success(
                elements = PdfLazyList(filePath, pageCount),
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

    private inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int
    ) : AbstractList<ContentElement>(), java.io.Closeable {
        
        private val pageCache = mutableStateMapOf<Int, ContentElement>().apply {
            globalContentCache.get(filePath)?.let { putAll(it) }
        }
        
        private val loadingJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
        
        private var pdfHandle: PdfDocumentHandle? = null
        private val mutex = Mutex()

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

            return ContentElement.Placeholder("Loading page ${index + 1}...", ESTIMATED_PAGE_HEIGHT_DP)
        }

        private fun triggerLoad(index: Int) {
            if (loadingJobs.containsKey(index)) return

            val job = loaderScope.launch {
                val elements = loadPageContent(index + 1)
                withContext(Dispatchers.Main) {
                    addToCache(index, ContentElement.PageContent(elements))
                    loadingJobs.remove(index)
                }
            }
            
            loadingJobs[index] = job

            if (loadingJobs.size > 10) {
                val furthestKey = loadingJobs.keys.maxByOrNull { abs(it - index) }
                if (furthestKey != null && furthestKey != index) {
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

        private fun getOrOpenDocument(): PdfDocument? {
            if (pdfHandle != null && !pdfHandle!!.document.isClosed) {
                return pdfHandle!!.document
            }

            // Clean up previous handle
            try { pdfHandle?.close() } catch (_: Exception) {}
            pdfHandle = null

            pdfHandle = openPdfDocument(filePath)
            return pdfHandle?.document
        }

        private suspend fun loadPageContent(pageNum: Int): List<ContentElement> = mutex.withLock {
            runCatching {
                val doc = getOrOpenDocument() ?: return emptyList()
                if (pageNum > doc.numberOfPages) return emptyList()

                val page = doc.getPage(pageNum)
                
                val strategy = CombinedExtractionStrategy()
                val processor = PdfCanvasProcessor(strategy)
                processor.processPageContent(page)
                
                val rawText = strategy.resultantText ?: ""
                val images = strategy.getImageElements()

                val paragraphs = mutableListOf<ContentElement>()
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

                if (paragraphs.isEmpty() && images.isEmpty() && rawText.isNotBlank()) {
                    paragraphs.add(ContentElement.Text(rawText.trim()))
                }

                paragraphs + images
            }.getOrDefault(emptyList())
        }

        override fun close() {
            try { pdfHandle?.close() } catch (_: Exception) {}
            pdfHandle = null
        }
    }

    private inner class CombinedExtractionStrategy : LocationTextExtractionStrategy() {
        private val imageChunks = mutableListOf<ContentElement.Image>()

        override fun getSupportedEvents(): Set<EventType>? {
            return setOf(EventType.RENDER_TEXT, EventType.RENDER_IMAGE)
        }

        override fun eventOccurred(data: IEventData, type: EventType) {
            if (type == EventType.RENDER_TEXT) {
                super.eventOccurred(data, type)
                return
            }
            
            if (type == EventType.RENDER_IMAGE) {
                val renderInfo = data as ImageRenderInfo
                try {
                    val imageObject = renderInfo.image ?: return
                    val imageBytes = imageObject.imageBytes ?: return

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                    val origWidth = options.outWidth
                    val origHeight = options.outHeight
                    if (origWidth <= 0 || origHeight <= 0) return

                    // Downsample large images to save memory
                    val sampleSize = calculateInSampleSize(origWidth, origHeight, IMAGE_DOWNSAMPLE_THRESHOLD)
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions) ?: return
                    
                    val fileName = "pdf_img_${UUID.randomUUID()}.webp"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                    }
                    bitmap.recycle()
                    
                    imageChunks.add(ContentElement.Image(
                        url = "file://${file.absolutePath}",
                        width = origWidth,
                        height = origHeight
                    ))
                } catch (_: Exception) {}
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

        fun getImageElements(): List<ContentElement.Image> = imageChunks
    }
}
