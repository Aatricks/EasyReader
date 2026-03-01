package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

@Singleton
class PdfContentLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d+$")
        private const val MAX_LOCAL_CACHE_SIZE = 100
        private const val MAX_GLOBAL_PDF_CACHE_SIZE = 5 // Keep last 5 PDFs in memory
        private const val MAX_GLOBAL_PAGE_CACHE_PER_PDF = 50 // Keep last 50 pages per PDF
    }

    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Global caches for instantaneous re-entry
    private val pageCountCache = ConcurrentHashMap<String, Int>()
    private val globalContentCache = LruCache<String, MutableMap<Int, ContentElement>>(MAX_GLOBAL_PDF_CACHE_SIZE)

    suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            // 1. Check page count cache first to skip slow PDF opening
            val cachedCount = pageCountCache[filePath]
            val pageCount = if (cachedCount != null) {
                cachedCount
            } else {
                val count = if (filePath.startsWith("content://")) {
                    val uri = Uri.parse(filePath)
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val channel = FileInputStream(pfd.fileDescriptor).channel
                        val source = com.itextpdf.io.source.FileChannelRandomAccessSource(channel)
                        val reader = PdfReader(source, ReaderProperties())
                        val doc = PdfDocument(reader)
                        val c = doc.numberOfPages
                        doc.close()
                        c
                    } ?: throw Exception("PDF not found")
                } else {
                    val file = File(filePath)
                    if (!file.exists()) throw Exception("PDF not found")
                    val doc = PdfDocument(PdfReader(filePath))
                    val c = doc.numberOfPages
                    doc.close()
                    c
                }
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

    private inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int
    ) : AbstractList<ContentElement>(), java.io.Closeable {
        
        // Cache to store loaded page content (PageContent element).
        // Initialized from global cache if available for instant re-entry.
        private val pageCache = mutableStateMapOf<Int, ContentElement>().apply {
            globalContentCache.get(filePath)?.let { putAll(it) }
        }
        
        // Track currently active page loading jobs to allow cancellation during rapid scrolling
        private val loadingJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
        
        private var pdfDocument: PdfDocument? = null
        private var pfd: ParcelFileDescriptor? = null
        private val lock = Any()
        private val mutex = Mutex()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            
            // 1. Return cached content if available (local or global)
            pageCache[index]?.let { return it }

            // 2. Trigger load and return placeholder
            triggerLoad(index)

            // 3. Prefetch next 2 pages in background
            for (i in 1..2) {
                val nextIndex = index + i
                if (nextIndex < size && !pageCache.containsKey(nextIndex)) {
                    triggerLoad(nextIndex)
                }
            }

            // Return a lightweight placeholder to avoid large layout costs during fast scrolling
            return ContentElement.Placeholder("Loading page ${index + 1}...")
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

            // Prevent excessive memory use and backlog during rapid scrolling.
            if (loadingJobs.size > 10) {
                val furthestKey = loadingJobs.keys.maxByOrNull { abs(it - index) }
                if (furthestKey != null && furthestKey != index) {
                    loadingJobs.remove(furthestKey)?.cancel()
                }
            }
        }

        private fun addToCache(index: Int, content: ContentElement) {
            // 1. Add to local Compose-observed cache
            pageCache[index] = content
            
            // 2. Add to global cache for re-entry
            synchronized(globalContentCache) {
                var docCache = globalContentCache.get(filePath)
                if (docCache == null) {
                    docCache = mutableMapOf()
                    globalContentCache.put(filePath, docCache)
                }
                docCache[index] = content
                
                // Trim doc-specific cache if it gets too large
                if (docCache.size > MAX_GLOBAL_PAGE_CACHE_PER_PDF) {
                    val firstKey = docCache.keys.firstOrNull()
                    if (firstKey != null) docCache.remove(firstKey)
                }
            }

            // 3. Trim local cache
            if (pageCache.size > MAX_LOCAL_CACHE_SIZE) {
                val furthestKey = pageCache.keys.maxByOrNull { 
                    if (it is Int) abs(it - index) else 0 
                }
                furthestKey?.let { pageCache.remove(it) }
            }
        }

        private fun getOrOpenDocument(): PdfDocument? {
            synchronized(lock) {
                if (pdfDocument != null && !pdfDocument!!.isClosed) {
                    return pdfDocument
                }

                // If we are reopening, make sure previous resources are cleared
                try { pfd?.close() } catch (e: Exception) {}
                pfd = null

                val doc = runCatching {
                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                        this@PdfLazyList.pfd = pfd
                        val channel = FileInputStream(pfd.fileDescriptor).channel
                        val source = com.itextpdf.io.source.FileChannelRandomAccessSource(channel)
                        PdfDocument(PdfReader(source, ReaderProperties()))
                    } else {
                        // Use the file path directly for better internal iText buffering
                        PdfDocument(PdfReader(filePath))
                    }
                }.getOrNull()

                pdfDocument = doc
                return doc
            }
        }

        private suspend fun loadPageContent(pageNum: Int): List<ContentElement> = mutex.withLock {
            runCatching {
                val doc = getOrOpenDocument() ?: return emptyList()
                if (pageNum > doc.numberOfPages) return emptyList()

                val page = doc.getPage(pageNum)
                
                // 1. Single Pass Extraction (Images + Text)
                val strategy = CombinedExtractionStrategy()
                val processor = PdfCanvasProcessor(strategy)
                processor.processPageContent(page)
                
                val rawText = strategy.resultantText ?: ""
                val images = strategy.getImageElements()

                // 2. Process text into paragraphs
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

                // 3. Fallback for non-standard whitespace PDFs
                if (paragraphs.isEmpty() && images.isEmpty() && rawText.isNotBlank()) {
                    paragraphs.add(ContentElement.Text(rawText.trim()))
                }

                paragraphs + images
            }.getOrDefault(emptyList())
        }

        override fun close() {
            synchronized(lock) {
                try {
                    pdfDocument?.close()
                } catch (e: Exception) {}
                pdfDocument = null

                try {
                    pfd?.close()
                } catch (e: Exception) {}
                pfd = null
            }
        }
    }

    private inner class CombinedExtractionStrategy : LocationTextExtractionStrategy() {
        private val imageChunks = mutableListOf<ContentElement.Image>()

        override fun getSupportedEvents(): Set<EventType>? {
            return setOf(EventType.RENDER_TEXT, EventType.RENDER_IMAGE)
        }

        override fun eventOccurred(data: IEventData, type: EventType) {
            // LocationTextExtractionStrategy handles RENDER_TEXT via super call
            super.eventOccurred(data, type)
            
            if (type == EventType.RENDER_IMAGE) {
                val renderInfo = data as ImageRenderInfo
                try {
                    val imageObject = renderInfo.image ?: return
                    val imageBytes = imageObject.imageBytes ?: return
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return
                    
                    val fileName = "pdf_img_${UUID.randomUUID()}.jpg"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    
                    imageChunks.add(ContentElement.Image(
                        url = "file://${file.absolutePath}",
                        width = bitmap.width,
                        height = bitmap.height
                    ))
                } catch (e: Exception) {}
            }
        }

        fun getImageElements(): List<ContentElement.Image> = imageChunks
    }

    private data class TextChunk(val text: String, val x: Float, val y: Float, val height: Float)
    private data class ImageChunk(val element: ContentElement.Image, val y: Float)
}
