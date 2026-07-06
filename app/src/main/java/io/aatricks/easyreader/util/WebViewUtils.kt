package io.aatricks.easyreader.util

import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
     * Validation is based on parsed URL scheme/host, not substring matching in path/query.
     *
     * When [expectedHost] is non-null, the navigation host must match it (or be a sub/super
     * domain of it) so a Cloudflare challenge cannot wander off to a third-party origin.
     */
    fun shouldAllowCloudflareNavigation(url: String?, expectedHost: String? = null): Boolean {
        if (url == null) return false

        // Parse first so only the actual URL scheme is validated.
        val parsedUrl = url.toHttpUrlOrNull() ?: return false
        if (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") return false

        // Keep host/IP safety checks centralized in UrlSecurity.
        if (!UrlSecurity.isSafeUrlSynchronous(parsedUrl)) return false

        if (expectedHost.isNullOrBlank()) return true
        val navHost = parsedUrl.host.lowercase()
        val expected = expectedHost.lowercase()
        return navHost == expected ||
            navHost.endsWith(".$expected") ||
            expected.endsWith(".$navHost")
    }
}
