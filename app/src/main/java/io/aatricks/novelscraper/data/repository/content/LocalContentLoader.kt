package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.net.Uri
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.data.repository.HtmlParser
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
    private val htmlParser: HtmlParser
) {
    suspend fun handleLocalFile(url: String, pdfLoader: PdfContentLoader, epubLoader: EpubContentLoader): ContentResult {
        val uri = Uri.parse(url)
        val mime = context.contentResolver.getType(uri) ?: return loadFileByExtension(url, pdfLoader, epubLoader)
        
        return when {
            mime.contains("pdf", ignoreCase = true) -> pdfLoader.loadPdfContent(url)
            mime.contains("epub", ignoreCase = true) || mime.contains("application/epub+zip", ignoreCase = true) -> epubLoader.loadEpubContent(url)
            mime.contains("html", ignoreCase = true) || mime.contains("text", ignoreCase = true) -> loadHtmlFile(url)
            else -> ContentResult.Error("Unsupported MIME type: $mime")
        }
    }

    suspend fun loadFileByExtension(url: String, pdfLoader: PdfContentLoader, epubLoader: EpubContentLoader): ContentResult =
        when {
            url.endsWith(".pdf", ignoreCase = true) -> pdfLoader.loadPdfContent(url)
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
