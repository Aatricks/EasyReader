package io.aatricks.novelscraper.data.repository.custom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class FetchedHtmlPage(
    val requestedUrl: String,
    val resolvedUrl: String,
    val html: String
)

interface RecipePageFetcher {
    suspend fun fetch(url: String, referer: String? = null): FetchedHtmlPage
}

@Singleton
class OkHttpRecipePageFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient
) : RecipePageFetcher {

    override suspend fun fetch(url: String, referer: String?): FetchedHtmlPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .apply {
                if (!referer.isNullOrBlank()) {
                    header("Referer", referer)
                }
            }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Unexpected code ${response.code}")
            }
            val body = response.body?.string() ?: throw java.io.IOException("Empty response")
            FetchedHtmlPage(
                requestedUrl = url,
                resolvedUrl = response.request.url.toString(),
                html = body
            )
        }
    }
}
