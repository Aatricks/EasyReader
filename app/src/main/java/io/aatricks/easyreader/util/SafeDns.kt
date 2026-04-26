package io.aatricks.easyreader.util

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * An OkHttp Dns implementation that rejects unsafe IP addresses (loopback, private, etc.).
 * This prevents SSRF and DNS rebinding attacks.
 */
class SafeDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        
        // Reject the whole hostname if any of its resolved addresses are unsafe.
        // This is conservative but necessary for security.
        val unsafeAddress = addresses.find { !UrlSecurity.isSafeInetAddress(it) }
        if (unsafeAddress != null) {
            throw UnknownHostException("Unsafe address resolved for $hostname: $unsafeAddress")
        }
        
        return addresses
    }
}
