package io.aatricks.easyreader

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.content.EpubImageFetcher
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import javax.inject.Inject

@HiltAndroidApp
class EasyReaderApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var contentRepository: ContentRepository

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache { buildMemoryCache(context) }
            .diskCache { buildDiskCache(context) }
            .components {
                add(OkHttpNetworkFetcherFactory(okHttpClient))
                add(EpubImageFetcher.Factory(contentRepository))
            }
            .crossfade(false)
            .build()
    }

    private fun buildMemoryCache(context: PlatformContext): MemoryCache {
        return MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()
    }

    private fun buildDiskCache(context: PlatformContext): DiskCache {
        val directory = context.cacheDir.resolve("image_cache").absolutePath.toPath()
        return DiskCache.Builder()
            .directory(directory)
            .maxSizeBytes(512 * 1024 * 1024)
            .build()
    }
}