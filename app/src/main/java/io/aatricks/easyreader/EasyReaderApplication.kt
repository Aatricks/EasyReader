package io.aatricks.easyreader

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.content.EpubImageFetcher
import io.aatricks.easyreader.data.repository.content.HttpMediaCacheFetcher
import io.aatricks.easyreader.util.CrashRecorder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class EasyReaderApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var contentRepository: ContentRepository
    @Inject lateinit var preferencesManager: PreferencesManager

    private val warmupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        prewarmLastReadChapter()
    }

    // Kick off chapter parse on a background coroutine so it overlaps Hilt graph build,
    // Compose first frame, and ViewModel init. Populates ParsedContentCache + in-memory memo,
    // so by the time ReaderViewModel.loadContent runs the fast path is already primed.
    // Fire-and-forget; failures are swallowed because pre-warm is best-effort.
    private fun prewarmLastReadChapter() {
        val url = preferencesManager.lastReadUrl?.takeIf { it.isNotBlank() } ?: return
        warmupScope.launch {
            runCatching { contentRepository.loadContent(url) }
                .onFailure { Log.w(TAG, "prewarm failed url=$url message=${it.message}") }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache { buildMemoryCache(context) }
            .components {
                add(EpubImageFetcher.Factory(contentRepository))
                add(HttpMediaCacheFetcher.Factory(contentRepository))
                add(OkHttpNetworkFetcherFactory(okHttpClient))
            }
            .crossfade(false)
            .build()
    }

    private fun buildMemoryCache(context: PlatformContext): MemoryCache {
        return MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()
    }

    companion object {
        private const val TAG = "EasyReaderApplication"
    }
}
