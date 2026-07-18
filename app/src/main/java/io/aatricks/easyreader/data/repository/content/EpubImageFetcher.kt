package io.aatricks.easyreader.data.repository.content

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.aatricks.easyreader.data.repository.ContentRepository
import okio.Path.Companion.toPath

/**
 * Coil fetcher for EPUB images stored within the EPUB zip file.
 * Handles URLs in the format: "epubPath#img:imgHref"
 */
class EpubImageFetcher(
    private val url: String,
    private val contentRepository: ContentRepository,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = contentRepository.getEpubImageFile(url) ?: return null
        
        return SourceFetchResult(
            source = ImageSource(
                file.absolutePath.toPath(),
                options.fileSystem
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory(
        private val contentRepository: ContentRepository
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.contains("#img:")) {
                return EpubImageFetcher(data, contentRepository, options)
            }
            return null
        }
    }
}
