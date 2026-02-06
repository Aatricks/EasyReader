package io.aatricks.novelscraper.util

import android.webkit.WebSettings
import android.webkit.WebView

object WebViewUtils {
    fun configureCloudflareWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // SECURITY: Explicitly disallow mixed content (loading HTTP resources on HTTPS pages)
            // to prevent Man-in-the-Middle (MitM) attacks.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }
}
