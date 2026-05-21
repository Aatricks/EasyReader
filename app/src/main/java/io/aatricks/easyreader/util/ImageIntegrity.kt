package io.aatricks.easyreader.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Integrity check for cached image files. Catches three failure modes that
 * `File.exists()` alone would miss:
 *   1. Zero-byte / single-byte files.
 *   2. HTML error pages (Cloudflare/CDN challenges) returned with an image content-type.
 *   3. Truncated downloads — a file with the correct magic header but missing trailer.
 *      Coil's decoder rejects these at read time and the user sees "Image unavailable"
 *      while the chapter badge still says Downloaded. The structural trailer check below
 *      keeps inspect honest with what the reader can actually decode.
 *
 * Trailer check covers JPEG/PNG/WebP — the formats real manga sources use. GIF/BMP/AVIF/SVG
 * fall back to the magic-byte-only check; if we encounter widespread truncation there we
 * add their trailers too.
 */
object ImageIntegrity {
    // Conservative lower bound — only rejects obviously-truncated downloads (zero bytes,
    // a few bytes of partial header). The magic-byte check is the real validator; the size
    // check just guards against `readHeader` returning a too-short array. PNG signature
    // alone is 8 bytes, JPEG SOI is 2 bytes, WebP needs 12 bytes for the RIFF+WEBP brand.
    private const val MIN_VALID_IMAGE_BYTES = 16L
    private const val SNIFF_BYTES = 32
    // Real CDN-served images often append metadata, watermarks, or padding after the
    // format's spec-required end marker. Search this many bytes back from EOF so we accept
    // valid images that aren't bit-for-bit spec-pure while still catching truncations
    // (which omit the marker entirely).
    private const val TRAILER_SCAN_BYTES = 512

    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_IMAGE_BYTES) return false
        val header = readHeader(file) ?: return false
        val kind = classifyFormat(header) ?: return false
        return when (kind) {
            ImageFormat.JPEG -> jpegLooksComplete(file)
            ImageFormat.PNG -> pngLooksComplete(file)
            ImageFormat.WEBP -> webpLooksComplete(file)
            // No trailer check defined; magic check alone — we accept these because the
            // download path is harder to truncate transparently and these formats are rare
            // in real chapter content.
            ImageFormat.GIF,
            ImageFormat.BMP,
            ImageFormat.AVIF_HEIF,
            ImageFormat.SVG -> true
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

    // JPEG EOI marker is FF D9. The spec puts it at end-of-file but many CDN-served JPEGs
    // append metadata/watermarks/padding after EOI. Scan the trailing window; a truncation
    // omits the marker entirely so absence is still a reliable failure signal.
    private val jpegEoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private fun jpegLooksComplete(file: File): Boolean {
        val tail = readTrailer(file, TRAILER_SCAN_BYTES) ?: return false
        return indexOfLast(tail, jpegEoi) >= 0
    }

    // PNG IEND chunk header: 4-byte length (always 0) + 4-byte type "IEND". Scan trailing
    // window — same rationale as JPEG: trailing bytes after IEND occur in the wild.
    private val pngIendHeader = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte()
    )
    private fun pngLooksComplete(file: File): Boolean {
        val tail = readTrailer(file, TRAILER_SCAN_BYTES) ?: return false
        return indexOfLast(tail, pngIendHeader) >= 0
    }

    private fun indexOfLast(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (start in (haystack.size - needle.size) downTo 0) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }

    // WebP RIFF header declares size of (file - 8). If the file is shorter than that, it
    // was cut off; longer is OK because chunks can be padded. Header layout:
    //   bytes 0..3   = "RIFF"
    //   bytes 4..7   = little-endian uint32 chunk size (covers bytes 8..end)
    //   bytes 8..11  = "WEBP"
    private fun webpLooksComplete(file: File): Boolean {
        val header = runCatching {
            file.inputStream().use { stream ->
                val bytes = ByteArray(12)
                val read = stream.read(bytes)
                if (read == 12) bytes else null
            }
        }.getOrNull() ?: return false
        val declared = (header[4].toInt() and 0xFF) or
            ((header[5].toInt() and 0xFF) shl 8) or
            ((header[6].toInt() and 0xFF) shl 16) or
            ((header[7].toInt() and 0xFF) shl 24)
        // declared is the size from offset 8 onward; total file size therefore is declared + 8.
        return file.length() >= declared.toLong() + 8L
    }

    private fun readHeader(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val bytes = ByteArray(SNIFF_BYTES)
            val read = stream.read(bytes)
            if (read <= 0) null else bytes.copyOf(read)
        }
    }.getOrNull()

    private fun readTrailer(file: File, byteCount: Int): ByteArray? = runCatching {
        val length = file.length()
        val want = byteCount.toLong().coerceAtMost(length).toInt()
        if (want <= 0) return@runCatching null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - want)
            val bytes = ByteArray(want)
            var read = 0
            while (read < want) {
                val n = raf.read(bytes, read, want - read)
                if (n == -1) return@runCatching null
                read += n
            }
            bytes
        }
    }.getOrNull()
}
