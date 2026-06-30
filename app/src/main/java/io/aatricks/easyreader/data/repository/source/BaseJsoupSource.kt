package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class BaseJsoupSource(
    protected open val preferencesManager: PreferencesManager,
    protected open val okHttpClient: okhttp3.OkHttpClient
) : NovelSource {

    companion object {
        private val MULTIPLE_SLASHES_REGEX = Regex("/+")
    }

    protected open val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    protected open val timeout = 15000L

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    protected fun getDocument(url: String): Document {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", baseUrl)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
            val html = response.body.string()
            return Jsoup.parse(html, url)
        }
    }

    protected fun Element.absoluteUrl(attributeKey: String): String {
        return resolveUrl(attr(attributeKey))
    }

    protected fun Element.findImage(): String {
        val candidates = listOf("data-src", "data-original", "data-lazy-src", "src")
        return candidates.firstNotNullOfOrNull { attr(it).takeIf { v -> v.isNotBlank() } } ?: ""
    }

    protected fun org.jsoup.nodes.Document.metaContent(
        property: String? = null,
        name: String? = null
    ): String? {
        val selectors = buildList {
            property?.let { add("meta[property=$it]") }
            name?.let { add("meta[name=$it]") }
        }
        return selectors.firstNotNullOfOrNull { sel ->
            selectFirst(sel)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    protected fun org.jsoup.nodes.Document.firstNonBlankText(selectors: List<String>): String? =
        selectors.firstNotNullOfOrNull { sel ->
            selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
        }

    protected fun resolveUrl(path: String): String {
        return when {
            path.isBlank() -> ""
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> if (path.startsWith(baseUrl)) path else "$baseUrl/$path"
        }.replace(MULTIPLE_SLASHES_REGEX, "/").replace("https:/", "https://").replace("http:/", "http://")
    }
}
