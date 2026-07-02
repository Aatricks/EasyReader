package io.aatricks.easyreader

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.data.repository.content.EpubImageFetcher
import io.aatricks.easyreader.data.repository.content.HttpMediaCacheFetcher
import io.aatricks.easyreader.util.CrashRecorder
import io.aatricks.easyreader.work.ChapterDownloadQueue
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class EasyReaderApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var contentRepository: ContentRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var chapterDownloadQueue: ChapterDownloadQueue
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var imageDimensionCache: ImageDimensionCacheRepository

    // WorkManager pulls this lazily before its first enqueue, which happens after Hilt
    // injection has populated `workerFactory`. Using on-demand initialization (no manual
    // `WorkManager.initialize`) means the test variant can override via WorkManagerTestInitHelper.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    private val warmupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        if (!resetLegacyWebOfflinePipelineIfNeeded()) {
            prewarmLastReadChapter()
        }
        pruneImageDimensionCache()
        pruneChapterDownloadQueue()
    }

    private fun pruneChapterDownloadQueue() {
        warmupScope.launch {
            runCatching { chapterDownloadQueue.prune() }
                .onFailure { Log.w(TAG, "chapter download queue prune failed message=${it.message}") }
        }
    }

    private fun resetLegacyWebOfflinePipelineIfNeeded(): Boolean {
        val storedVersion = preferencesManager.webOfflinePipelineVersion
        if (storedVersion >= WEB_OFFLINE_PIPELINE_VERSION) return false
        warmupScope.launch {
            if (storedVersion < 2) {
                runCatching {
                    val previouslyDownloadedWeb = libraryRepository.getDownloadedItems()
                        .filter { it.contentType == ContentType.WEB }
                    contentRepository.resetWebOfflinePipelineData()
                    previouslyDownloadedWeb.forEach { item ->
                        libraryRepository.markDownloaded(item.id, false)
                        chapterDownloadQueue.enqueue(item.url, replaceExisting = true)
                    }
                    preferencesManager.webOfflinePipelineVersion = WEB_OFFLINE_PIPELINE_VERSION
                    Log.i(TAG, "reset legacy web offline data and requeued ${previouslyDownloadedWeb.size} chapters")
                }.onFailure {
                    Log.w(TAG, "web offline reset failed message=${it.message}")
                }
            } else if (storedVersion == 2) {
                runCatching {
                    contentRepository.sweepLegacyWebDownloadArtifacts()
                    preferencesManager.webOfflinePipelineVersion = WEB_OFFLINE_PIPELINE_VERSION
                    Log.i(
                        TAG,
                        "swept legacy web download artifacts for version $WEB_OFFLINE_PIPELINE_VERSION"
                    )
                }.onFailure {
                    Log.w(TAG, "web offline sweep failed message=${it.message}")
                }
            }
            prewarmLastReadChapter()
        }
        return true
    }

    private fun pruneImageDimensionCache() {
        warmupScope.launch {
            runCatching { imageDimensionCache.prune() }
                .onFailure { Log.w(TAG, "image dim cache prune failed message=${it.message}") }
        }
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
                add(io.aatricks.easyreader.data.repository.content.ReaderImageTileFetcher.Factory(contentRepository))
                add(EpubImageFetcher.Factory(contentRepository))
                add(HttpMediaCacheFetcher.Factory(contentRepository))
                // Fallback only: HttpMediaCacheFetcher owns the disk-cached HTTP path and
                // matches every http(s) URL. OkHttp's fetcher runs only if that one returns
                // null (offline + cache miss, or a Factory.create bug). Keep it so a
                // regression in our fetcher doesn't render images unfetchable.
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
        private const val WEB_OFFLINE_PIPELINE_VERSION = 3
    }
}
