package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfContentLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d+$")
        private val PARAGRAPH_SPLIT_REGEX = Regex("\\n\\s*\\n")
    }

    suspend fun loadPdfContent(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val pageCount = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use {
                    PdfDocument(PdfReader(it)).use { doc -> doc.numberOfPages }
                } ?: throw Exception("PDF not found")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("PDF not found")
                PdfDocument(PdfReader(file)).use { doc -> doc.numberOfPages }
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
        }.getOrElse { e ->
            ContentResult.Error("PDF Error: ${e.message}")
        }
    }

    private inner class PdfLazyList(
        private val filePath: String,
        private val totalPages: Int
    ) : AbstractList<ContentElement>(), java.io.Closeable {
        private var pdfDocument: PdfDocument? = null
        private var inputStream: java.io.InputStream? = null
        private val lock = Any()

        override val size: Int get() = totalPages

        override fun get(index: Int): ContentElement {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
            val text = loadPageText(index + 1)
            return ContentElement.Text(text)
        }

        private fun getOrOpenDocument(): PdfDocument? {
            synchronized(lock) {
                if (pdfDocument != null && !pdfDocument!!.isClosed) {
                    return pdfDocument
                }

                val doc = runCatching {
                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        val stream = context.contentResolver.openInputStream(uri) ?: return null
                        inputStream = stream
                        PdfDocument(PdfReader(stream))
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

        private fun loadPageText(pageNum: Int): String {
            return runCatching {
                val doc = getOrOpenDocument() ?: return ""
                if (pageNum > doc.numberOfPages) return ""

                val rawText = PdfTextExtractor.getTextFromPage(doc.getPage(pageNum))

                rawText.lines()
                    .filterNot { it.trim().matches(PAGE_NUMBER_REGEX) }
                    .joinToString("\n")
                    .split(PARAGRAPH_SPLIT_REGEX)
                    .map { it.trim() }
                    .filter { it.length > 20 }
                    .joinToString("\n\n")
            }.getOrDefault("")
        }

        override fun close() {
            synchronized(lock) {
                try {
                    pdfDocument?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                pdfDocument = null

                try {
                    inputStream?.close()
                } catch (e: Exception) {
                    // Ignore stream close errors
                }
                inputStream = null
            }
        }
    }
}
