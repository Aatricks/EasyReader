package io.aatricks.novelscraper.util

import android.webkit.WebSettings
import android.webkit.WebView
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
    }
}
