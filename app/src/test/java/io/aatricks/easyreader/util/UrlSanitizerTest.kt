package io.aatricks.easyreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun `null and blank become placeholder`() {
        assertEquals("<no url>", UrlSanitizer.sanitize(null))
        assertEquals("<no url>", UrlSanitizer.sanitize(""))
        assertEquals("<no url>", UrlSanitizer.sanitize("   "))
    }

    @Test
    fun `https with single path segment keeps segment`() {
        assertEquals(
            "https://novelfire.net/book",
            UrlSanitizer.sanitize("https://novelfire.net/book")
        )
    }

    @Test
    fun `https with deeper path collapses tail`() {
        assertEquals(
            "https://novelfire.net/book/…",
            UrlSanitizer.sanitize("https://novelfire.net/book/the-novel/chapter-42?utm=leak#x")
        )
    }

    @Test
    fun `http with no path keeps host only`() {
        assertEquals(
            "http://example.com",
            UrlSanitizer.sanitize("http://example.com")
        )
    }

    @Test
    fun `content scheme keeps scheme only`() {
        assertEquals(
            "content://…",
            UrlSanitizer.sanitize("content://com.android.providers.media/document/12345")
        )
    }

    @Test
    fun `file scheme keeps scheme only`() {
        assertEquals(
            "file://…",
            UrlSanitizer.sanitize("file:///storage/emulated/0/secret.epub")
        )
    }

    @Test
    fun `non-url input becomes placeholder`() {
        assertEquals("<unparseable url>", UrlSanitizer.sanitize("not a url"))
        assertEquals("<unparseable url>", UrlSanitizer.sanitize("/leading/slash"))
    }

    @Test
    fun `query string is dropped`() {
        assertEquals(
            "https://example.com/page",
            UrlSanitizer.sanitize("https://example.com/page?token=secret&id=42")
        )
    }
}
