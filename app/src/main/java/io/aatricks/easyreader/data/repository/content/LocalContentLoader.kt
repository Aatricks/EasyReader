package io.aatricks.easyreader.data.repository.content

import android.content.Context
import android.net.Uri
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.repository.HtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalContentLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val htmlParser: HtmlParser,
    private val pdfLoader: PdfContentLoader,
    private val epubLoader: EpubContentLoader,
    private val contentUriTypeResolver: ContentUriTypeResolver
) {
    suspend fun loadLocalContent(url: String, pdfResumeIndex: Int? = null): ContentResult {
        if (!url.startsWith("content://") && !url.startsWith("file://")) {
            return loadFileByExtension(url, pdfResumeIndex)
        }

        val uri = Uri.parse(url)
        val mime = contentUriTypeResolver.resolveMimeType(url)
            ?: return loadFileByExtension(url, pdfResumeIndex)
        
        return when {
            mime.contains("pdf", ignoreCase = true) -> pdfLoader.loadPdfContent(url, pdfResumeIndex)
            mime.contains("epub", ignoreCase = true) || mime.contains("application/epub+zip", ignoreCase = true) -> epubLoader.loadEpubContent(url)
            mime.contains("html", ignoreCase = true) || mime.contains("text", ignoreCase = true) -> loadHtmlFile(url)
            else -> ContentResult.Error("Unsupported MIME type: $mime")
        }
    }

    private suspend fun loadFileByExtension(
        url: String,
        pdfResumeIndex: Int? = null
    ): ContentResult =
        when {
            url.endsWith(".pdf", ignoreCase = true) -> pdfLoader.loadPdfContent(url, pdfResumeIndex)
            url.endsWith(".epub", ignoreCase = true) -> epubLoader.loadEpubContent(url)
            url.endsWith(".html", ignoreCase = true) || url.endsWith(".htm", ignoreCase = true) -> loadHtmlFile(url)
            else -> ContentResult.Error("Unsupported local file type")
        }

    suspend fun loadHtmlFile(filePath: String): ContentResult = withContext(Dispatchers.IO) {
        runCatching {
            val document = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { 
                    Jsoup.parse(it.readText(), uri.toString()) 
                } ?: throw Exception("Unable to read $filePath")
            } else {
                val file = File(filePath)
                if (!file.exists()) throw Exception("File not found")
                Jsoup.parse(file, "UTF-8")
            }
            
            ContentResult.Success(
                elements = htmlParser.parse(document, filePath),
                title = document.title().takeIf { it.isNotBlank() },
                url = filePath
            )
        }.getOrElse { e ->
            ContentResult.Error("Failed to load HTML: ${e.message}")
        }
    }
}
