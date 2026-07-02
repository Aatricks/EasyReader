package io.aatricks.easyreader.data.repository.content

import java.io.File

internal object ImageBoundsParser {
    private const val VP8_START_CODE_0 = 0x9D
    private const val VP8_START_CODE_1 = 0x01
    private const val VP8_START_CODE_2 = 0x2A
    private const val VP8_START_CODE_OFFSET = 23
    private const val VP8_WIDTH_OFFSET = 26
    private const val VP8_HEIGHT_OFFSET = 28
    private const val VP8_DIMENSION_MASK = 0x3FFF

    fun parse(file: File): Pair<Int, Int>? {
        if (!file.exists()) return null
        return runCatching { parse(file.readBytes()) }.getOrNull()
    }

    fun parse(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        return parsePng(bytes) ?: parseJpeg(bytes) ?: parseWebP(bytes)
    }

    private fun parsePng(bytes: ByteArray): Pair<Int, Int>? {
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (!bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return null
        if (bytes.size < 24) return null
        val width = readInt32(bytes, 16)
        val height = readInt32(bytes, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun parseJpeg(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null

        var offset = 2
        while (offset + 9 < bytes.size) {
            while (offset < bytes.size && bytes[offset] != 0xFF.toByte()) {
                offset++
            }
            while (offset < bytes.size && bytes[offset] == 0xFF.toByte()) {
                offset++
            }
            if (offset >= bytes.size) return null

            val marker = bytes[offset].toInt() and 0xFF
            offset++

            if (marker == 0xD9 || marker == 0xDA) return null
            if (offset + 1 >= bytes.size) return null

            val segmentLength = readInt16(bytes, offset)
            if (segmentLength < 2 || offset + segmentLength > bytes.size) return null

            if (marker in setOf(
                    0xC0, 0xC1, 0xC2, 0xC3,
                    0xC5, 0xC6, 0xC7,
                    0xC9, 0xCA, 0xCB,
                    0xCD, 0xCE, 0xCF
                )
            ) {
                if (offset + 7 >= bytes.size) return null
                val height = readInt16(bytes, offset + 3)
                val width = readInt16(bytes, offset + 5)
                return if (width > 0 && height > 0) width to height else null
            }

            offset += segmentLength
        }

        return null
    }

    private fun parseWebP(bytes: ByteArray): Pair<Int, Int>? {
        if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WEBP")) return null
        if (bytes.size < 30) return null
        return when {
            matchesAscii(bytes, 12, "VP8 ") -> parseLossyWebP(bytes)
            matchesAscii(bytes, 12, "VP8X") -> {
                val width = 1 + readInt24(bytes, 24)
                val height = 1 + readInt24(bytes, 27)
                if (width > 0 && height > 0) width to height else null
            }
            matchesAscii(bytes, 12, "VP8L") && bytes.size >= 25 -> {
                val b0 = bytes[21].toInt() and 0xFF
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val width = 1 + (b0 or ((b1 and 0x3F) shl 8))
                val height = 1 + (((b1 and 0xC0) shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
                if (width > 0 && height > 0) width to height else null
            }
            else -> null
        }
    }

    private fun parseLossyWebP(bytes: ByteArray): Pair<Int, Int>? {
        val startCodeOk = bytes[VP8_START_CODE_OFFSET] == VP8_START_CODE_0.toByte() &&
            bytes[VP8_START_CODE_OFFSET + 1] == VP8_START_CODE_1.toByte() &&
            bytes[VP8_START_CODE_OFFSET + 2] == VP8_START_CODE_2.toByte()
        if (startCodeOk) {
            val width = ((bytes[VP8_WIDTH_OFFSET].toInt() and 0xFF) or
                ((bytes[VP8_WIDTH_OFFSET + 1].toInt() and 0xFF) shl 8)) and VP8_DIMENSION_MASK
            val height = ((bytes[VP8_HEIGHT_OFFSET].toInt() and 0xFF) or
                ((bytes[VP8_HEIGHT_OFFSET + 1].toInt() and 0xFF) shl 8)) and VP8_DIMENSION_MASK
            if (width > 0 && height > 0) return width to height
        }
        return null
    }

    private fun readInt16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readInt24(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, expected: String): Boolean {
        if (offset + expected.length > bytes.size) return false
        return expected.indices.all { index ->
            bytes[offset + index].toInt().toChar() == expected[index]
        }
    }
}
