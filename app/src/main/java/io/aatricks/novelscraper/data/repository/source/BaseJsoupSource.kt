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
        val value = attr(attributeKey)
        return when {
            value.isBlank() -> ""
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$baseUrl$value"
            else -> "$baseUrl/$value"
        }
    }

    protected fun Element.findImage(): String {
        return attr("data-src").ifBlank {
            attr("data-original").ifBlank {
                attr("data-lazy-src").ifBlank {
                    attr("src")
                }
            }
        }
    }

    protected fun resolveUrl(path: String): String {
        return when {
            path.isBlank() -> ""
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> "$baseUrl/$path"
        }
    }
}
