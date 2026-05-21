package io.aatricks.easyreader.util

import java.io.File

/**
 * Cheap integrity check for cached image files. Catches truncated downloads, zero-byte files,
 * and HTML error pages (Cloudflare/CDN challenges) returned with an image content-type that
 * `File.exists()` alone would treat as a successful download.
 */
object ImageIntegrity {
    // Conservative lower bound — only rejects obviously-truncated downloads (zero bytes,
    // a few bytes of partial header). The magic-byte check is the real validator; the size
    // check just guards against `readHeader` returning a too-short array. PNG signature
    // alone is 8 bytes, JPEG SOI is 2 bytes, WebP needs 12 bytes for the RIFF+WEBP brand.
    private const val MIN_VALID_IMAGE_BYTES = 16L
    private const val SNIFF_BYTES = 32

    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_IMAGE_BYTES) return false
        val header = readHeader(file) ?: return false
        return classify(header) == ImageKind.Image
    }

    private enum class ImageKind { Image, Html, Unknown }

    private fun classify(header: ByteArray): ImageKind {
        if (looksLikeImage(header)) return ImageKind.Image
        if (looksLikeHtml(header)) return ImageKind.Html
        return ImageKind.Unknown
    }

    private fun looksLikeImage(header: ByteArray): Boolean {
        if (header.size < 4) return false
        // JPEG: FF D8 FF
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return true
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()) return true
        // GIF87a / GIF89a
        if (header.size >= 6 &&
            header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte() &&
            (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte()) return true
        // WebP: "RIFF" .... "WEBP"
        if (header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) return true
        // BMP: "BM"
        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return true
        // AVIF / HEIF: ftyp box at offset 4 (skip 4-byte size), then "ftyp" + brand
        if (header.size >= 12 &&
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) return true
        // SVG: starts with "<svg" or "<?xml" followed by svg
        val prefix = header.decodeToString().trimStart().lowercase()
        if (prefix.startsWith("<svg")) return true
        if (prefix.startsWith("<?xml") && prefix.contains("<svg")) return true
        return false
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
}
