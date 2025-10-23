package io.aatricks.novelscraper.util

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
     * @param context Application context
     * @param uri The URI to extract filename from
     * @return The filename or null if not found
     */
    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null

        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }

        // Fallback to last path segment if content scheme fails
        if (result == null) {
            result = uri.lastPathSegment
        }

        return result
    }

    /**
     * Get the file extension from a URI
     * @param context Application context
     * @param uri The URI to extract extension from
     * @return The file extension (without dot) or empty string
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
     * @param context Application context
     * @param uri The URI to get MIME type from
     * @return MIME type string or null
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    /**
     * Detect file type from URI based on MIME type and extension
     * @param context Application context
     * @param uri The URI to detect type from
     * @return FileType enum value
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
     * @param context Application context
     * @param uri The URI to read from
     * @return InputStream or null if failed
     */
    fun getInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Copy URI content to a file
     * @param context Application context
     * @param uri Source URI
     * @param destinationFile Destination file
     * @return true if successful, false otherwise
     */
    fun copyUriToFile(context: Context, uri: Uri, destinationFile: File): Boolean {
        return try {
            val inputStream = getInputStream(context, uri) ?: return false
            inputStream.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Get file size from URI
     * @param context Application context
     * @param uri The URI to get size from
     * @return File size in bytes or -1 if unknown
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = -1
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        return size
    }

    /**
     * Format file size to human-readable string
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "1.5 MB")
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

        return String.format("%.2f %s", size, units[unitIndex])
    }

    /**
     * Check if URI is a local file
     * @param uri The URI to check
     * @return true if local file, false otherwise
     */
    fun isLocalFile(uri: Uri): Boolean {
        return uri.scheme == "file"
    }

    /**
     * Check if URI is a content URI
     * @param uri The URI to check
     * @return true if content URI, false otherwise
     */
    fun isContentUri(uri: Uri): Boolean {
        return uri.scheme == "content"
    }

    /**
     * Check if URI is a remote URL
     * @param uri The URI to check
     * @return true if remote URL, false otherwise
     */
    fun isRemoteUrl(uri: Uri): Boolean {
        return uri.scheme == "http" || uri.scheme == "https"
    }

    /**
     * Validate if a string is a valid URL
     * @param url The URL string to validate
     * @return true if valid URL, false otherwise
     */
    fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")
        } catch (e: Exception) {
            false
        }
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
