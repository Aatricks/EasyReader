package io.aatricks.easyreader.data.repository.content

import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.httpHeaders
import coil3.request.Options
import io.aatricks.easyreader.data.repository.ContentRepository
import okio.Path.Companion.toPath

val ChapterPageUrlExtra: Extras.Key<String?> = Extras.Key(default = null)

class HttpMediaCacheFetcher(
    private val url: String,
    private val contentRepository: ContentRepository,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val pageUrl = options.extras[ChapterPageUrlExtra]
        val chapterDownloaded = pageUrl != null && contentRepository.isDownloaded(pageUrl)
        val tier = if (chapterDownloaded) StorageTier.DOWNLOADS else StorageTier.CACHE

        val cachedFile = contentRepository.getCachedMediaFile(url)
        val file = if (cachedFile.exists()) {
            if (chapterDownloaded && !contentRepository.isImageDownloaded(url)) {
                contentRepository.promoteImageToDownloads(url) ?: cachedFile
            } else {
                cachedFile
            }
        } else {
            val referer = pageUrl ?: requestReferer()
            contentRepository.downloadAndCacheImage(url, referer, tier) ?: return null
        }

        if (!file.exists() || file.length() <= 0L) return null

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
