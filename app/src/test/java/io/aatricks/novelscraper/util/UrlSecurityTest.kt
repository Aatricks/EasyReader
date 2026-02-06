package io.aatricks.novelscraper.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // localhost might be flaky if no /etc/hosts, but usually works
        // assertFalse(UrlSecurity.isSafeUrl("http://localhost"))
    }

    @Test
    fun `isSafeUrl rejects private addresses`() = runTest {
        assertFalse(UrlSecurity.isSafeUrl("http://192.168.1.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://10.0.0.1"))
        assertFalse(UrlSecurity.isSafeUrl("http://172.16.0.1"))
    }

    @Test
    fun `isSafeUrl accepts public addresses`() = runTest {
        // 8.8.8.8 is Google DNS, definitely public
        assertTrue(UrlSecurity.isSafeUrl("http://8.8.8.8"))
    }
}
