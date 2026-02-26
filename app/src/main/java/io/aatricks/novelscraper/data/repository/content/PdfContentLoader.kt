package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateMapOf
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
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
import java.util.Collections
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
        
        // Cache to store loaded page text. 
        // Using mutableStateMapOf allows Compose to observe reads and trigger recomposition when data arrives.
        private val pageCache = mutableStateMapOf<Int, String>()
        
        // Track currently loading pages to prevent duplicate requests
        private val loadingPages = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        
        private var pdfDocument: PdfDocument? = null
        private var pfd: ParcelFileDescriptor? = null
        private val lock = Any()
        private val mutex = Mutex()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            
            // 1. Return cached content if available
            pageCache[index]?.let { return ContentElement.Text(it) }

            // 2. If not cached, trigger load and return placeholder
            if (loadingPages.add(index)) {
                loaderScope.launch {
                    val text = loadPageText(index + 1)
                    withContext(Dispatchers.Main) {
                        addToCache(index, text)
                        loadingPages.remove(index)
                    }
                }
            }

            // Return a "tall" placeholder to prevent LazyListState from clamping scroll offset during restoration.
            // Using a large number of newlines ensures the placeholder is likely taller than any typical page.
            return ContentElement.Text("Loading page ${index + 1}..." + "\n".repeat(100))
        }

        private fun addToCache(index: Int, text: String) {
            pageCache[index] = text
            if (pageCache.size > MAX_CACHE_SIZE) {
                // Evict the page furthest from the current index to keep memory stable
                val furthestKey = pageCache.keys.maxByOrNull { abs(it - index) }
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

        private suspend fun loadPageText(pageNum: Int): String = mutex.withLock {
            runCatching {
                val doc = getOrOpenDocument() ?: return ""
                if (pageNum > doc.numberOfPages) return ""

                val rawText = PdfTextExtractor.getTextFromPage(doc.getPage(pageNum))

                rawText.lines()
                    .filterNot { it.trim().matches(PAGE_NUMBER_REGEX) }
                    .joinToString("\n")
                    .split(PARAGRAPH_SPLIT_REGEX)
                    .map { it.trim() }
                    .filter { it.length > 2 } // Relaxed filter to keep more PDF content
                    .joinToString("\n\n")
            }.getOrDefault("")
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
}
