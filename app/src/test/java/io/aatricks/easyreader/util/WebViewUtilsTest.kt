package io.aatricks.easyreader.util

import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class WebViewUtilsTest {
    @Test
    fun `configureCloudflareWebView sets secure mixed content mode`() {
        val mockWebView = mock(WebView::class.java)
        val mockSettings = mock(WebSettings::class.java)

        // When asking for settings, return our mock settings
        `when`(mockWebView.settings).thenReturn(mockSettings)

        // Run the configuration
        WebViewUtils.configureCloudflareWebView(mockWebView)

        // Verify that mixed content is set to NEVER_ALLOW (Security Fix)
        verify(mockSettings).mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Verify other important settings
        verify(mockSettings).javaScriptEnabled = true
        verify(mockSettings).domStorageEnabled = true
        
        // Verify hardening settings
        verify(mockSettings).allowFileAccess = false
        verify(mockSettings).allowContentAccess = false
        verify(mockSettings).setSupportMultipleWindows(false)
        verify(mockSettings).javaScriptCanOpenWindowsAutomatically = false
    }

    @Test
    fun `shouldAllowCloudflareNavigation allows safe https urls`() {
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("https://example.com/path"))
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("http://example.com/path"))
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("HTTPS://www.google.com/search?q=test"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks non-http schemes`() {
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("file:///etc/passwd"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("content://media/external/images/media"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("intent://something"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("javascript:alert(1)"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("data:text/html,<html><body>Hacked</body></html>"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("mailto:test@example.com"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("tel:123"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks unsafe hosts via UrlSecurity`() {
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("http://127.0.0.1"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("http://localhost"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("http://169.254.169.254"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation allows blocked-scheme words in query when parsed scheme is safe`() {
        assertTrue(
            WebViewUtils.shouldAllowCloudflareNavigation(
                "https://example.com/search?q=file:data:intent:javascript:"
            )
        )
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks malformed urls`() {
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("https://"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("not a url"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation(null))
    }
}
