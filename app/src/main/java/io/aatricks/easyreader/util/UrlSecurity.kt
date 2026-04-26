package io.aatricks.easyreader.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object UrlSecurity {

    suspend fun isSafeUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        isSafeUrlSynchronous(url)
    }

    fun isSafeUrlSynchronous(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return isSafeUrlSynchronous(httpUrl)
    }

    fun isSafeUrlSynchronous(httpUrl: okhttp3.HttpUrl): Boolean {
        // 1. Validate Scheme
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return false
        }

        val host = httpUrl.host

        // 2. Initial host check (fast path for common unsafe hosts)
        if (isUnsafeHostLiteral(host)) return false

        // Note: We no longer do one-shot InetAddress.getByName(host) here as the 
        // primary security boundary to prevent DNS rebinding. 
        // Enforcement happens in SafeDns at connection time.
        return true
    }

    /**
     * Checks if a host literal is obviously unsafe (e.g. "localhost", "127.0.0.1").
     * This avoids one-shot DNS precheck of hostnames.
     */
    fun isUnsafeHostLiteral(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        
        // If it looks like an IP address, validate it
        if (isIpLiteral(host)) {
            return runCatching {
                val address = InetAddress.getByName(host)
                !isSafeInetAddress(address)
            }.getOrDefault(false)
        }
        
        return false
    }

    private fun isIpLiteral(host: String): Boolean {
        // Simple heuristic for IPv4 or IPv6 literals
        // IPv4: digits and dots
        // IPv6: contains colons, may be wrapped in brackets
        return host.all { it.isDigit() || it == '.' } || 
               host.contains(':') || 
               (host.startsWith('[') && host.endsWith(']'))
    }

    /**
     * Comprehensive check for safe public IP addresses.
     * Blocks:
     * - Loopback (127.0.0.0/8, ::1)
     * - Any-local (0.0.0.0, ::)
     * - Link-local (169.254.0.0/16, fe80::/10)
     * - Site-local / Private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7)
     * - Multicast (224.0.0.0/4, ff00::/8)
     * - Carrier-grade NAT (100.64.0.0/10)
     * - 0.0.0.0/8
     * - IPv4-mapped IPv6 versions of the above
     */
    fun isSafeInetAddress(address: InetAddress): Boolean {
        // Basic checks from InetAddress
        if (address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address

        if (address is Inet4Address) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            when (b0) {
                0 -> return false // 0.0.0.0/8
                10 -> return false // 10.0.0.0/8 (covered by isSiteLocalAddress but explicit is better)
                100 -> if (b1 in 64..127) return false // 100.64.0.0/10 (CGNAT)
                127 -> return false // 127.0.0.0/8 (covered by isLoopbackAddress)
                169 -> if (b1 == 254) return false // 169.254.0.0/16 (covered by isLinkLocalAddress)
                172 -> if (b1 in 16..31) return false // 172.16.0.0/12 (covered by isSiteLocalAddress)
                192 -> if (b1 == 168) return false // 192.168.0.0/16 (covered by isSiteLocalAddress)
            }
        } else if (address is Inet6Address) {
            // IPv6 checks
            // isLoopbackAddress covers ::1
            // isAnyLocalAddress covers ::
            // isLinkLocalAddress covers fe80::/10
            // isSiteLocalAddress covers fec0::/10 (deprecated)
            
            // Unique Local Address (ULA): fc00::/7
            val b0 = bytes[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return false

            // IPv4-mapped IPv6 address: ::ffff:0:0/96
            // We should extract the IPv4 part and check it
            if (isIpv4MappedIpv6(bytes)) {
                val ipv4Bytes = bytes.sliceArray(12..15)
                val ipv4Address = InetAddress.getByAddress(ipv4Bytes)
                return isSafeInetAddress(ipv4Address)
            }
        }

        return true
    }

    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0..9) {
            if (bytes[i] != 0.toByte()) return false
        }
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }
}
