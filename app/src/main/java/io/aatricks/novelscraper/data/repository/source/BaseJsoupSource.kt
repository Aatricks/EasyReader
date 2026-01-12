package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class BaseJsoupSource : NovelSource {
    protected open val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    protected open val timeout = 15000

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    protected fun connect(url: String): Connection = Jsoup.connect(url)
        .userAgent(userAgent)
        .referrer(baseUrl)
        .timeout(timeout)
        .followRedirects(true)

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
