package io.aatricks.novelscraper.util

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.net.UnknownHostException

class SafeDnsTest {

    private val delegate: Dns = mock()
    private val safeDns = SafeDns(delegate)

    @Test
    fun `lookup returns addresses for safe public hostname`() {
        val hostname = "google.com"
        val addresses = listOf(InetAddress.getByName("8.8.8.8"))
        whenever(delegate.lookup(hostname)).thenReturn(addresses)

        val result = safeDns.lookup(hostname)
        assertEquals(addresses, result)
    }

    @Test
    fun `lookup rejects hostname resolving to loopback`() {
        val hostname = "evil.com"
        val addresses = listOf(InetAddress.getByName("127.0.0.1"))
        whenever(delegate.lookup(hostname)).thenReturn(addresses)

        try {
            safeDns.lookup(hostname)
            fail("Should have thrown UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Unsafe address resolved"))
        }
    }

    @Test
    fun `lookup rejects hostname resolving to private IP`() {
        val hostname = "internal.corp"
        val addresses = listOf(InetAddress.getByName("192.168.1.1"))
        whenever(delegate.lookup(hostname)).thenReturn(addresses)

        try {
            safeDns.lookup(hostname)
            fail("Should have thrown UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Unsafe address resolved"))
        }
    }

    @Test
    fun `lookup rejects hostname resolving to mixed safe and unsafe IPs`() {
        // DNS Rebinding simulation: one safe IP, one unsafe IP
        val hostname = "rebind.evil.com"
        val addresses = listOf(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("10.0.0.1")
        )
        whenever(delegate.lookup(hostname)).thenReturn(addresses)

        try {
            safeDns.lookup(hostname)
            fail("Should have thrown UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Unsafe address resolved"))
        }
    }

    @Test
    fun `lookup rejects hostname resolving to IPv6 loopback`() {
        val hostname = "localhost6"
        val addresses = listOf(InetAddress.getByName("::1"))
        whenever(delegate.lookup(hostname)).thenReturn(addresses)

        try {
            safeDns.lookup(hostname)
            fail("Should have thrown UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Unsafe address resolved"))
        }
    }

    private fun assertTrue(condition: Boolean) {
        if (!condition) fail()
    }
}
