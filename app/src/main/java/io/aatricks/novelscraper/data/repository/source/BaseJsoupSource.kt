package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

abstract class BaseJsoupSource(
    protected open val preferencesManager: PreferencesManager? = null,
    protected open val okHttpClient: okhttp3.OkHttpClient? = null
) : NovelSource {
    protected open val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    protected open val timeout = 15000L

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    protected fun getDocument(url: String): Document {
        val client = okHttpClient
        if (client != null) {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", baseUrl)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
                val html = response.body?.string() ?: throw java.io.IOException("Empty response")
                return Jsoup.parse(html, url)
            }
        }
        
        // Fallback to Jsoup's connection if okHttpClient is not available
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(timeout.toInt())
            .followRedirects(true)
        
        if (preferencesManager?.ignoreSslErrors == true) {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            connection.sslSocketFactory(sslContext.socketFactory)
            // Note: setDefaultHostnameVerifier is global and might affect other parts of the app
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }
        
        return connection.get()
    }

    protected fun connect(url: String): Connection {
        // This method is now legacy as we prefer getDocument with okHttpClient
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(timeout.toInt())
            .followRedirects(true)
        
        if (preferencesManager?.ignoreSslErrors == true) {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            connection.sslSocketFactory(sslContext.socketFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }
        
        return connection
    }

    protected fun Element.absoluteUrl(attributeKey: String): String {
        return resolveUrl(attr(attributeKey))
    }

    protected fun Element.findImage(): String {
        val candidates = listOf("data-src", "data-original", "data-lazy-src", "src")
        return candidates.firstNotNullOfOrNull { attr(it).takeIf { v -> v.isNotBlank() } } ?: ""
    }

    protected fun resolveUrl(path: String): String {
        return when {
            path.isBlank() -> ""
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> if (path.startsWith(baseUrl)) path else "$baseUrl/$path"
        }.replace(Regex("/+"), "/").replace("https:/", "https://").replace("http:/", "http://")
    }
}
