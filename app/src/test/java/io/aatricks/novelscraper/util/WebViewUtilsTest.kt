package io.aatricks.novelscraper.util

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
        verify(mockSettings).allowFileAccessFromFileURLs = false
        verify(mockSettings).allowUniversalAccessFromFileURLs = false
        verify(mockSettings).setSupportMultipleWindows(false)
        verify(mockSettings).javaScriptCanOpenWindowsAutomatically = false
    }

    @Test
    fun `shouldAllowCloudflareNavigation allows safe https urls`() {
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("https://example.com"))
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("https://www.google.com/search?q=test"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation allows about blank`() {
        assertTrue(WebViewUtils.shouldAllowCloudflareNavigation("about:blank"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks non-http schemes`() {
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("file:///etc/passwd"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("content://media/external/images/media"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("intent:#Intent;scheme=http;package=com.android.chrome;end"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks risky content`() {
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("javascript:alert(1)"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("https://example.com/login?next=javascript:alert(1)"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("data:text/html,<html><body>Hacked</body></html>"))
    }

    @Test
    fun `shouldAllowCloudflareNavigation blocks unsafe hosts via UrlSecurity`() {
        // localhost is blocked by UrlSecurity
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("https://localhost"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("https://127.0.0.1"))
        assertFalse(WebViewUtils.shouldAllowCloudflareNavigation("https://192.168.1.1"))
    }
}
