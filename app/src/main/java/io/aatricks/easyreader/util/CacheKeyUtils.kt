package io.aatricks.easyreader.util

import java.security.MessageDigest

object CacheKeyUtils {
    fun keyFor(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"

        digest.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            chars[index * 2] = hex[unsigned ushr 4]
            chars[index * 2 + 1] = hex[unsigned and 0x0f]
        }

        return String(chars)
    }
}
