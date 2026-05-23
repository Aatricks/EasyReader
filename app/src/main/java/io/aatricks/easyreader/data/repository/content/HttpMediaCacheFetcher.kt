package io.aatricks.easyreader.data.repository.content

import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import android.util.Log
import coil3.network.httpHeaders
import coil3.request.Options
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.util.UrlSanitizer
import okio.Path.Companion.toPath

val ChapterPageUrlExtra: Extras.Key<String?> = Extras.Key(default = null)

class HttpMediaCacheFetcher(
    private val url: String,
    private val contentRepository: ContentRepository,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val pageUrl = options.extras[ChapterPageUrlExtra]?.takeIf { it.isNotBlank() }
        val safeUrl = UrlSanitizer.sanitize(url)

        val cachedFile = contentRepository.findUsableCachedMediaFile(url)
        Log.d(
            "HttpMediaCacheFetcher",
            "fetch img=$safeUrl cacheHit=${cachedFile != null}"
        )

        val file = if (cachedFile != null) {
            cachedFile
        } else {
            contentRepository.invalidateCachedMediaFile(url, pageUrl)
            val referer = pageUrl ?: requestReferer()
            val refetched = contentRepository.downloadAndCacheImage(url, referer)
            if (refetched == null) {
                Log.w("HttpMediaCacheFetcher", "refetch FAILED img=$safeUrl (offline or network error)")
                return null
            }
            refetched
        }

        if (!file.exists() || file.length() <= 0L) {
            Log.w("HttpMediaCacheFetcher", "final file missing img=$safeUrl path=${file.absolutePath} exists=${file.exists()} len=${file.length()}")
            return null
        }

        return SourceFetchResult(
            source = ImageSource(file.absolutePath.toPath(), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    private fun requestReferer(): String {
        return runCatching { options.httpHeaders.get("Referer") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url
    }

    class Factory(
        private val contentRepository: ContentRepository
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.startsWith("http://") || data.startsWith("https://")) {
                return HttpMediaCacheFetcher(data, contentRepository, options)
            }
            return null
        }
    }

}
