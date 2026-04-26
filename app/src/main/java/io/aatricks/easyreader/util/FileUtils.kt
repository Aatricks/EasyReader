package io.aatricks.easyreader.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * Utility functions for file operations.
 * Handles file picker results, URI conversions, and file type detection.
 */
object FileUtils {

    /**
     * Get the filename from a URI
     */
    fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment

        return queryContentColumn(context, uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment
    }

    private fun queryContentColumn(context: Context, uri: Uri, columnName: String): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(columnName), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(columnName)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        }.getOrNull()
    }

    /**
     * Get the file extension from a URI
     */
    fun getFileExtension(context: Context, uri: Uri): String {
        val fileName = getFileName(context, uri) ?: return ""
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex >= 0) {
            fileName.substring(lastDotIndex + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * Get MIME type from URI
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    /**
     * Detect file type from URI based on MIME type and extension
     */
    fun detectFileType(context: Context, uri: Uri): FileType {
        val mimeType = getMimeType(context, uri)
        val extension = getFileExtension(context, uri)

        return when {
            mimeType == "application/pdf" || extension == "pdf" -> FileType.PDF
            mimeType == "text/html" || 
            mimeType == "application/xhtml+xml" || 
            extension == "html" || 
            extension == "htm" -> FileType.HTML
            mimeType == "application/epub+zip" || 
            extension == "epub" -> FileType.EPUB
            else -> FileType.UNKNOWN
        }
    }

    /**
     * Read InputStream from URI
     */
    fun getInputStream(context: Context, uri: Uri): InputStream? {
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * Copy URI content to a file
     */
    fun copyUriToFile(context: Context, uri: Uri, destinationFile: File): Boolean {
        return runCatching {
            getInputStream(context, uri)?.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } != null
        }.getOrDefault(false)
    }

    /**
     * Get file size from URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme != "content") return -1L
        
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
                } else -1L
            }
        }.getOrNull() ?: -1L
    }

    /**
     * Format file size to human-readable string
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "Unknown"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return "%.2f %s".format(size, units[unitIndex])
    }

    /**
     * Check if URI is a local file
     */
    fun isLocalFile(uri: Uri): Boolean {
        return uri.scheme == "file"
    }

    /**
     * Check if URI is a content URI
     */
    fun isContentUri(uri: Uri): Boolean {
        return uri.scheme == "content"
    }

    /**
     * Check if URI is a remote URL
     */
    fun isRemoteUrl(uri: Uri): Boolean {
        return uri.scheme == "http" || uri.scheme == "https"
    }

    /**
     * Validate if a string is a valid URL
     */
    fun isValidUrl(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")
        }.getOrDefault(false)
    }

    /**
     * Enum representing supported file types
     */
    enum class FileType {
        PDF,
        HTML,
        EPUB,
        UNKNOWN
    }
}
