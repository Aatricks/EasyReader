package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
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
        private val PARAGRAPH_SPLIT_REGEX = Regex("\\n\\s*\\n")
        private const val MAX_CACHE_SIZE = 100
    }

    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        try {
            val pageCount = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val channel = FileInputStream(pfd.fileDescriptor).channel
                    val source = com.itextpdf.io.source.FileChannelRandomAccessSource(channel)
                    val reader = PdfReader(source, ReaderProperties())
                    val doc = PdfDocument(reader)
                    val count = doc.numberOfPages
                    doc.close()
                    count
                } ?: throw Exception("PDF not found")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("PDF not found")
                val doc = PdfDocument(PdfReader(file))
                val count = doc.numberOfPages
                doc.close()
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
        
        // Cache to store loaded page content (PageContent element)
        private val pageCache = mutableStateMapOf<Int, ContentElement>()
        
        // Track currently active page loading jobs to allow cancellation during rapid scrolling
        private val loadingJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
        
        private var pdfDocument: PdfDocument? = null
        private var pfd: ParcelFileDescriptor? = null
        private val lock = Any()
        private val mutex = Mutex()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            
            // 1. Return cached content if available
            pageCache[index]?.let { return it }

            // 2. If not cached, trigger load and return placeholder
            if (!loadingJobs.containsKey(index)) {
                val job = loaderScope.launch {
                    val elements = loadPageContent(index + 1)
                    withContext(Dispatchers.Main) {
                        addToCache(index, ContentElement.PageContent(elements))
                        loadingJobs.remove(index)
                    }
                }
                loadingJobs[index] = job

                // Prevent queue buildup during rapid scrolling
                if (loadingJobs.size > 5) {
                    val furthestKey = loadingJobs.keys.maxByOrNull { abs(it - index) }
                    if (furthestKey != null && furthestKey != index) {
                        loadingJobs.remove(furthestKey)?.cancel()
                    }
                }
            }

            // Return a lightweight placeholder to avoid large layout costs during fast scrolling
            return ContentElement.Placeholder("Loading page ${index + 1}...")
        }

        private fun addToCache(index: Int, content: ContentElement) {
            pageCache[index] = content
            if (pageCache.size > MAX_CACHE_SIZE) {
                // Evict the page furthest from the current index to keep memory stable
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
                        val file = File(filePath)
                        if (!file.exists()) return null
                        PdfDocument(PdfReader(file))
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
                
                // 1. Extract Images
                val strategy = ImageExtractionStrategy()
                val processor = PdfCanvasProcessor(strategy)
                processor.processPageContent(page)
                val images = strategy.getImageElements()

                // 2. Extract Text using proven method
                val rawText = PdfTextExtractor.getTextFromPage(page)
                
                // 3. Process text into paragraphs
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

                // 4. Combine
                if (paragraphs.isEmpty() && images.isEmpty() && rawText.isNotBlank()) {
                    // Fallback for non-standard whitespace PDFs
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

    private inner class ImageExtractionStrategy : LocationTextExtractionStrategy() {
        private val imageChunks = mutableListOf<ContentElement.Image>()

        override fun getSupportedEvents(): Set<EventType> {
            return setOf(EventType.RENDER_IMAGE)
        }

        override fun eventOccurred(data: IEventData, type: EventType) {
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
