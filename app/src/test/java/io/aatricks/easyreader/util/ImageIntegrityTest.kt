package io.aatricks.easyreader.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageIntegrityTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `rejects zero-byte file`() {
        val file = tempFolder.newFile("empty.jpg").apply { writeBytes(ByteArray(0)) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects file below minimum size threshold`() {
        val file = tempFolder.newFile("tiny.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts JPEG with SOI header and EOI trailer`() {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(60) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val file = tempFolder.newFile("ok.jpg").apply { writeBytes(payload) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts JPEG with trailing metadata after EOI`() {
        // Real CDN-served JPEGs frequently append EXIF tails, watermarks, anti-scrape
        // padding etc. after the spec-required EOI. The integrity check must accept these.
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(60) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) +
            "trailing watermark".toByteArray()
        val file = tempFolder.newFile("trailing.jpg").apply { writeBytes(payload) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts PNG with trailing metadata after IEND`() {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
            0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
        val file = tempFolder.newFile("trailing.png").apply {
            writeBytes(signature + ByteArray(32) + iend + "extra-junk".toByteArray())
        }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects JPEG missing EOI trailer (truncated mid-image)`() {
        // Truncated download: header parses fine, but body is cut and EOI never written.
        // Previously this passed the magic-byte check and inspect counted it as Downloaded.
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(60)
        val file = tempFolder.newFile("truncated.jpg").apply { writeBytes(payload) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts PNG with IEND chunk`() {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        // IEND chunk: 4-byte length=0, 4-byte type "IEND", 4-byte CRC (any value).
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
            0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
        val file = tempFolder.newFile("ok.png").apply { writeBytes(signature + ByteArray(32) + iend) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects PNG missing IEND chunk (truncated)`() {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val file = tempFolder.newFile("truncated.png").apply { writeBytes(signature + ByteArray(32)) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts WebP with declared chunk size matching file length`() {
        // RIFF declared size = 24 means total file size = 32. Pad payload to 32 bytes total.
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            24, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        ) + ByteArray(20)
        val file = tempFolder.newFile("ok.webp").apply { writeBytes(webp) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects WebP truncated below declared chunk size`() {
        // RIFF claims 200 bytes of payload but file only carries 20 — truncated.
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            200.toByte(), 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        ) + ByteArray(20)
        val file = tempFolder.newFile("truncated.webp").apply { writeBytes(webp) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects HTML payload masquerading as image`() {
        val html = "<!DOCTYPE html><html><body>Cloudflare challenge</body></html>".toByteArray()
        val file = tempFolder.newFile("html.jpg").apply { writeBytes(html) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts SVG`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".toByteArray()
        val file = tempFolder.newFile("ok.svg").apply { writeBytes(svg) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `rejects random unrecognized payload`() {
        val payload = "this is not an image at all just random text".toByteArray()
        val file = tempFolder.newFile("garbage.jpg").apply { writeBytes(payload) }
        assertFalse(ImageIntegrity.isValidImageFile(file))
    }
}
