package io.aatricks.novelscraper.util

import io.aatricks.novelscraper.data.local.PreferencesManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkUtils {
    
    private var preferencesManager: PreferencesManager? = null
    
    fun initialize(prefs: PreferencesManager) {
        preferencesManager = prefs
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // We usually let WebView handle cookie saving/loading if bypassed
            // But we can store them here too if needed
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieString = preferencesManager?.cookies ?: return emptyList()
            return cookieString.split(";").mapNotNull {
                Cookie.parse(url, it.trim())
            }
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .build()
    }

    fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to (preferencesManager?.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }
    
    fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Checking your browser", ignoreCase = true) ||
               html.contains("cloudflare.com/5xx-error-landing", ignoreCase = true) ||
               html.contains("Ray ID:", ignoreCase = true) ||
               html.contains("challenge-running", ignoreCase = true)
    }
}
