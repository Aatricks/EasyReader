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
    protected open val preferencesManager: PreferencesManager? = null
) : NovelSource {
    protected open val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    protected open val timeout = 15000

    private val trustAllSslSocketFactory: SSLSocketFactory by lazy {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    protected fun connect(url: String): Connection {
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(timeout)
            .followRedirects(true)
        
        if (preferencesManager?.ignoreSslErrors == true) {
            connection.sslSocketFactory(trustAllSslSocketFactory)
        }
        
        return connection
    }

    protected fun getDocument(url: String): Document = connect(url).get()

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
