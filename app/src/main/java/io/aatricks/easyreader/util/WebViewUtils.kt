package io.aatricks.easyreader.util

import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewUtils {
    /**
     * Configures a WebView with hardened settings suitable for Cloudflare challenges.
     * Enables only minimum required capabilities while disabling risky surfaces.
     */
    fun configureCloudflareWebView(webView: WebView) {
        webView.settings.apply {
            // Required for Cloudflare challenges
            javaScriptEnabled = true
            domStorageEnabled = true
            
            // SECURITY: Disable file and content access to prevent local data exfiltration
            allowFileAccess = false
            allowContentAccess = false
            
            // SECURITY: Disable access from file URLs
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            
            // SECURITY: Prevent JS from opening new windows or multiple windows
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            
            // SECURITY: Enable Safe Browsing if supported (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }

            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            
            // SECURITY: Explicitly disallow mixed content (loading HTTP resources on HTTPS pages)
            // to prevent Man-in-the-Middle (MitM) attacks.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    /**
     * Determines if a navigation request within the Cloudflare WebView should be allowed.
     * Blocks non-http/https schemes and unsafe hosts.
     */
    fun shouldAllowCloudflareNavigation(url: String?): Boolean {
        if (url == null) return false

        // Special case: allow about:blank as it's often used for cleanup or initial state
        if (url.equals("about:blank", ignoreCase = true)) return true

        // 1. Only allow http/https schemes
        if (!url.startsWith("http://", ignoreCase = true) && 
            !url.startsWith("https://", ignoreCase = true)) {
            return false
        }

        // 2. Block risky schemes that might be embedded or used in redirects
        val lowerUrl = url.lowercase()
        val blockedSchemes = listOf(
            "javascript:", "file:", "content:", "data:", 
            "intent:", "market:", "tel:", "mailto:", "about:"
        )
        if (blockedSchemes.any { lowerUrl.contains(it) }) {
            return false
        }

        // 3. Use UrlSecurity for deep validation (hosts, private IPs, DNS rebinding prevention)
        return UrlSecurity.isSafeUrlSynchronous(url)
    }
}
