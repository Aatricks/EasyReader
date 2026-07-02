package io.aatricks.easyreader.data.repository.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageBoundsParserTest {
    // Helper to write string to ByteArray
    private fun ByteArray.writeString(offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, this, offset, bytes.size)
    }

    private fun ByteArray.writeInt16Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.writeInt16Be(offset: Int, value: Int) {
        this[offset] = ((value shr 8) and 0xFF).toByte()
        this[offset + 1] = (value and 0xFF).toByte()
    }

    private fun ByteArray.writeInt24Le(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun ByteArray.writeInt32Be(offset: Int, value: Int) {
        this[offset] = ((value shr 24) and 0xFF).toByte()
        this[offset + 1] = ((value shr 16) and 0xFF).toByte()
        this[offset + 2] = ((value shr 8) and 0xFF).toByte()
        this[offset + 3] = (value and 0xFF).toByte()
    }

    @Test
    fun `parses lossy VP8 webp dimensions`() {
        val bytes = ByteArray(30)
        bytes.writeString(0, "RIFF")
        bytes.writeInt32Be(4, 22)
        bytes.writeString(8, "WEBP")
        bytes.writeString(12, "VP8 ")
        bytes.writeInt32Be(16, 10)
        // 3-byte frame tag at 20-22
        bytes[23] = 0x9D.toByte()
        bytes[24] = 0x01.toByte()
        bytes[25] = 0x2A.toByte()
        bytes.writeInt16Le(26, 800)
        bytes.writeInt16Le(28, 12000)

        val result = ImageBoundsParser.parse(bytes)
        assertEquals(800 to 12000, result)
    }

    @Test
    fun `rejects lossy VP8 without keyframe start code`() {
        val bytes = ByteArray(30)
        bytes.writeString(0, "RIFF")
        bytes.writeInt32Be(4, 22)
        bytes.writeString(8, "WEBP")
        bytes.writeString(12, "VP8 ")
        bytes.writeInt32Be(16, 10)
        // 3-byte frame tag at 20-22
        bytes[23] = 0x9E.toByte() // wrong start code
        bytes[24] = 0x01.toByte()
        bytes[25] = 0x2A.toByte()
        bytes.writeInt16Le(26, 800)
        bytes.writeInt16Le(28, 12000)

        val result = ImageBoundsParser.parse(bytes)
        assertNull(result)
    }

    @Test
    fun `masks the two upper bits of VP8 dimensions`() {
        // Case 1: Masked values (0xFFFF masked with 0x3FFF should be 0x3FFF)
        val bytes1 = ByteArray(30)
        bytes1.writeString(0, "RIFF")
        bytes1.writeString(8, "WEBP")
        bytes1.writeString(12, "VP8 ")
        bytes1[23] = 0x9D.toByte()
        bytes1[24] = 0x01.toByte()
        bytes1[25] = 0x2A.toByte()
        bytes1.writeInt16Le(26, 0xFFFF)
        bytes1.writeInt16Le(28, 0xFFFF)
        assertEquals(0x3FFF to 0x3FFF, ImageBoundsParser.parse(bytes1))

        // Case 2: In-range values
        val bytes2 = ByteArray(30)
        bytes2.writeString(0, "RIFF")
        bytes2.writeString(8, "WEBP")
        bytes2.writeString(12, "VP8 ")
        bytes2[23] = 0x9D.toByte()
        bytes2[24] = 0x01.toByte()
        bytes2[25] = 0x2A.toByte()
        bytes2.writeInt16Le(26, 1024)
        bytes2.writeInt16Le(28, 2048)
        assertEquals(1024 to 2048, ImageBoundsParser.parse(bytes2))
    }

    @Test
    fun `parses VP8X extended webp dimensions`() {
        val bytes = ByteArray(30)
        bytes.writeString(0, "RIFF")
        bytes.writeString(8, "WEBP")
        bytes.writeString(12, "VP8X")
        bytes.writeInt24Le(24, 100 - 1)
        bytes.writeInt24Le(27, 200 - 1)

        val result = ImageBoundsParser.parse(bytes)
        assertEquals(100 to 200, result)
    }

    @Test
    fun `parses VP8L lossless webp dimensions`() {
        val bytes = ByteArray(30)
        bytes.writeString(0, "RIFF")
        bytes.writeString(8, "WEBP")
        bytes.writeString(12, "VP8L")
        bytes[21] = 63.toByte()
        bytes[22] = 0xC0.toByte()
        bytes[23] = 31.toByte()
        bytes[24] = 0.toByte()

        val result = ImageBoundsParser.parse(bytes)
        assertEquals(64 to 128, result)
    }

    @Test
    fun `parses png dimensions`() {
        val bytes = ByteArray(24)
        // PNG Signature: 89 50 4E 47 0D 0A 1A 0A
        bytes[0] = 0x89.toByte()
        bytes[1] = 0x50.toByte()
        bytes[2] = 0x4E.toByte()
        bytes[3] = 0x47.toByte()
        bytes[4] = 0x0D.toByte()
        bytes[5] = 0x0A.toByte()
        bytes[6] = 0x1A.toByte()
        bytes[7] = 0x0A.toByte()

        bytes.writeInt32Be(16, 500)
        bytes.writeInt32Be(20, 400)

        val result = ImageBoundsParser.parse(bytes)
        assertEquals(500 to 400, result)
    }

    @Test
    fun `parses jpeg SOF0 dimensions`() {
        val bytes = ByteArray(15)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xC0.toByte() // SOF0
        bytes.writeInt16Be(4, 9)
        bytes[6] = 8 // Precision
        bytes.writeInt16Be(7, 150)
        bytes.writeInt16Be(9, 300)
        bytes[11] = 3

        val result = ImageBoundsParser.parse(bytes)
        assertEquals(300 to 150, result)
    }

    @Test
    fun `returns null for unknown payloads`() {
        val bytes = ByteArray(40) { it.toByte() }
        val result = ImageBoundsParser.parse(bytes)
        assertNull(result)
    }
}
