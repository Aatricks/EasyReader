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
    fun `accepts JPEG with SOI marker`() {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val file = tempFolder.newFile("ok.jpg").apply { writeBytes(payload) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts PNG with full 8-byte signature`() {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val file = tempFolder.newFile("ok.png").apply { writeBytes(signature + ByteArray(32)) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
    }

    @Test
    fun `accepts WebP with RIFF and WEBP brand`() {
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        ) + ByteArray(16)
        val file = tempFolder.newFile("ok.webp").apply { writeBytes(webp) }
        assertTrue(ImageIntegrity.isValidImageFile(file))
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
