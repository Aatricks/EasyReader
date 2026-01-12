package io.aatricks.novelscraper

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import javax.inject.Inject

@HiltAndroidApp
class EasyReaderApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache { buildMemoryCache(context) }
            .diskCache { buildDiskCache(context) }
            .components { add(OkHttpNetworkFetcherFactory(okHttpClient)) }
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