@file:Suppress("MagicNumber")

package io.aatricks.easyreader.util

import java.io.File

/**
 * Shallow integrity check for cached image files. Catches the failure modes
 * that `File.exists()` alone would miss while accepting only formats this app's
 * registered Coil decoders can render reliably:
 *   1. Zero-byte / single-byte files.
 *   2. HTML error pages (Cloudflare/CDN challenges) returned with an image content-type.
 *   3. Totally wrong payloads with no supported image magic bytes.
 */
object ImageIntegrity {
    // Conservative lower bound — only rejects obviously-truncated downloads (zero bytes,
    // a few bytes of partial header). The magic-byte check is the real validator; the size
    // check just guards against `readHeader` returning a too-short array. PNG signature
    // alone is 8 bytes, JPEG SOI is 2 bytes, WebP needs 12 bytes for the RIFF+WEBP brand.
    private const val MIN_VALID_IMAGE_BYTES = 16L
    private const val SNIFF_BYTES = 32
    private const val TAIL_BYTES = 64 * 1024

    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_IMAGE_BYTES) return false
        val header = readHeader(file) ?: return false
        return when (classifyFormat(header) ?: return false) {
            ImageFormat.JPEG -> hasJpegEndMarker(file)
            ImageFormat.PNG -> hasPngEndChunk(file)
            ImageFormat.GIF -> hasGifTrailer(file)
            ImageFormat.WEBP -> hasCompleteRiffPayload(header, file.length())
            ImageFormat.BMP -> hasCompleteBmpPayload(header, file.length())
            // These are valid image containers, but this app does not register decoders
            // that can render them reliably across supported Android versions. Counting
            // them as downloaded produces "Downloaded" rows that open to "Image unavailable".
            ImageFormat.AVIF_HEIF,
            ImageFormat.SVG -> false
        }
    }

    private enum class ImageFormat { JPEG, PNG, GIF, WEBP, BMP, AVIF_HEIF, SVG }

    private fun classifyFormat(header: ByteArray): ImageFormat? {
        if (header.size < 4) return null
        if (looksLikeHtml(header)) return null
        // JPEG: FF D8 FF
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return ImageFormat.JPEG
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()) return ImageFormat.PNG
        // GIF87a / GIF89a
        if (header.size >= 6 &&
            header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte() &&
            (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte()) return ImageFormat.GIF
        // WebP: "RIFF" .... "WEBP"
        if (header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) return ImageFormat.WEBP
        // BMP: "BM"
        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return ImageFormat.BMP
        // AVIF / HEIF: ftyp box at offset 4 (skip 4-byte size), then "ftyp" + brand
        if (header.size >= 12 &&
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) return ImageFormat.AVIF_HEIF
        // SVG: starts with "<svg" or "<?xml" followed by svg
        val prefix = header.decodeToString().trimStart().lowercase()
        if (prefix.startsWith("<svg")) return ImageFormat.SVG
        if (prefix.startsWith("<?xml") && prefix.contains("<svg")) return ImageFormat.SVG
        return null
    }

    private fun looksLikeHtml(header: ByteArray): Boolean {
        val prefix = header.decodeToString().trimStart().lowercase()
        if (prefix.startsWith("<!doctype")) return true
        if (prefix.startsWith("<html")) return true
        if (prefix.startsWith("<head")) return true
        if (prefix.startsWith("<body")) return true
        return false
    }

    private fun readHeader(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val bytes = ByteArray(SNIFF_BYTES)
            val read = stream.read(bytes)
            if (read <= 0) null else bytes.copyOf(read)
        }
    }.getOrNull()

    private fun hasJpegEndMarker(file: File): Boolean =
        readTail(file)?.containsSequence(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) == true

    private fun hasPngEndChunk(file: File): Boolean =
        readTail(file)?.containsSequence(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte()
            )
        ) == true

    private fun hasGifTrailer(file: File): Boolean =
        readTail(file)?.contains(0x3B.toByte()) == true

    private fun hasCompleteRiffPayload(header: ByteArray, fileLength: Long): Boolean {
        if (header.size < 8) return false
        val declaredPayloadSize =
            (header[4].toLong() and 0xFF) or
                ((header[5].toLong() and 0xFF) shl 8) or
                ((header[6].toLong() and 0xFF) shl 16) or
                ((header[7].toLong() and 0xFF) shl 24)
        return fileLength >= declaredPayloadSize + 8L
    }

    private fun hasCompleteBmpPayload(header: ByteArray, fileLength: Long): Boolean {
        if (header.size < 6) return false
        val declaredFileSize =
            (header[2].toLong() and 0xFF) or
                ((header[3].toLong() and 0xFF) shl 8) or
                ((header[4].toLong() and 0xFF) shl 16) or
                ((header[5].toLong() and 0xFF) shl 24)
        return declaredFileSize >= 14L && fileLength >= declaredFileSize
    }

    private fun readTail(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val skip = (file.length() - TAIL_BYTES).coerceAtLeast(0L)
            if (skip > 0) stream.skip(skip)
            stream.readBytes()
        }
    }.getOrNull()

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (size < needle.size) return false
        for (start in 0..(size - needle.size)) {
            var matched = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
