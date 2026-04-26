package io.aatricks.novelscraper.util

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UrlSecurityTest {

    @Test
    fun `isSafeUrl rejects non-http schemes`() = runTest {
        assertFalse(UrlSecurity.isSafeUrl("ftp://google.com"))
        assertFalse(UrlSecurity.isSafeUrl("file:///etc/passwd"))
        assertFalse(UrlSecurity.isSafeUrl("content://provider"))
        assertFalse(UrlSecurity.isSafeUrl("javascript:alert(1)"))
    }

    @Test
    fun `isSafeUrl rejects loopback addresses`() = runTest {
        assertFalse(UrlSecurity.isSafeUrl("http://127.0.0.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://localhost"))
        assertFalse(UrlSecurity.isSafeUrl("http://[::1]"))
    }

    @Test
    fun `isSafeUrl rejects private addresses`() = runTest {
        assertFalse(UrlSecurity.isSafeUrl("http://192.168.1.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://10.0.0.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://172.16.0.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://[fc00::1]"))
        assertFalse(UrlSecurity.isSafeUrl("http://[fd00::1]"))
    }

    @Test
    fun `isSafeUrl accepts public addresses`() = runTest {
        // 8.8.8.8 is Google DNS, definitely public
        assertTrue(UrlSecurity.isSafeUrl("http://8.8.8.8"))
        assertTrue(UrlSecurity.isSafeUrl("https://google.com"))
        assertTrue(UrlSecurity.isSafeUrl("http://[2001:4860:4860::8888]"))
    }

    @Test
    fun `isSafeInetAddress blocks various unsafe IPs`() {
        val unsafe = listOf(
            "127.0.0.1", "127.255.255.255",
            "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255",
            "192.168.0.1", "192.168.255.255",
            "169.254.1.1", "169.254.169.254",
            "0.0.0.0", "0.255.255.255",
            "100.64.0.1", "100.127.255.255",
            "224.0.0.1", "239.255.255.255",
            "::1", "::",
            "fe80::1", "febf::ffff",
            "fc00::1", "fdff::ffff",
            "ff00::1",
            "::ffff:127.0.0.1", // IPv4-mapped loopback
            "::ffff:192.168.1.1" // IPv4-mapped private
        )

        for (ip in unsafe) {
            val addr = InetAddress.getByName(ip)
            assertFalse("Should be unsafe: $ip", UrlSecurity.isSafeInetAddress(addr))
        }
    }

    @Test
    fun `isSafeInetAddress allows public IPs`() {
        val safe = listOf(
            "8.8.8.8", "1.1.1.1", "208.67.222.222",
            "2001:4860:4860::8888", "2606:4700:4700::1111"
        )

        for (ip in safe) {
            val addr = InetAddress.getByName(ip)
            assertTrue("Should be safe: $ip", UrlSecurity.isSafeInetAddress(addr))
        }
    }

    @Test
    fun `isSafeUrlSynchronous HttpUrl overload works`() {
        assertTrue(UrlSecurity.isSafeUrlSynchronous("https://google.com".toHttpUrl()))
        assertFalse(UrlSecurity.isSafeUrlSynchronous("http://localhost".toHttpUrl()))
    }
}
