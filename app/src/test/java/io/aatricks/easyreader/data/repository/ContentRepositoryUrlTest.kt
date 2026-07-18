package io.aatricks.easyreader.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.content.EpubContentLoader
import io.aatricks.easyreader.data.repository.content.LocalContentLoader
import io.aatricks.easyreader.data.repository.content.PdfContentLoader
import io.aatricks.easyreader.data.repository.content.ContentUriTypeResolver
import io.aatricks.easyreader.data.repository.content.StorageTier
import io.aatricks.easyreader.data.repository.content.WebContentLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

@ExperimentalCoroutinesApi
class ContentRepositoryUrlTest {

    private val webLoader = mock<WebContentLoader>()
    private val pdfLoader = mock<PdfContentLoader>()
    private val epubLoader = mock<EpubContentLoader>()
    private val localLoader = mock<LocalContentLoader>()
    private val context = mock<Context>()
    private val contentResolver = mock<ContentResolver>()
    private val okHttpClient = mock<OkHttpClient>()
    private val okHttpCache = mock<Cache>()
    private val contentUriTypeResolver = mock<ContentUriTypeResolver>()

    private lateinit var tempCacheDir: File
    private lateinit var repository: ContentRepository

    @Before
    fun setup() {
        tempCacheDir = kotlin.io.path.createTempDirectory("content-repository-url-test").toFile()
        whenever(context.cacheDir).thenReturn(tempCacheDir)
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(okHttpClient.cache).thenReturn(okHttpCache)

        repository = ContentRepository(
            webLoader,
            pdfLoader,
            epubLoader,
            localLoader,
            contentUriTypeResolver,
            context,
            okHttpClient
        )
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun verifyChapterUrlLogic() = runTest {
        assertEquals("http://example.com/chapter-2", repository.incrementChapterUrl("http://example.com/chapter-1"))
        assertEquals("http://example.com/ch2", repository.incrementChapterUrl("http://example.com/ch1"))
        assertEquals("http://example.com/chapter_2", repository.incrementChapterUrl("http://example.com/chapter_1"))
        
        assertEquals("http://example.com/chapter-1", repository.decrementChapterUrl("http://example.com/chapter-2"))
        
        assertNull(repository.decrementChapterUrl("http://example.com/chapter-1"))
    }

    @Test
    fun clearCachesForUrls_deduplicates_and_routes_by_type() = runTest {
        whenever(contentUriTypeResolver.resolveMimeType(any())).thenAnswer { invocation ->
            when (invocation.arguments[0] as String) {
                "content://com.example.provider/epub-item" -> "application/epub+zip"
                "content://com.example.provider/pdf-item" -> "application/pdf"
                else -> null
            }
        }

        val cleared = repository.clearCachesForUrls(
            listOf(
                "https://example.com/chapter-1",
                "https://example.com/chapter-1",
                "file:///tmp/book.epub",
                "file:///tmp/chapter.pdf",
                "content://com.example.provider/epub-item",
                "content://com.example.provider/pdf-item",
                "  "
            )
        )

        assertEquals(5, cleared)
        verify(epubLoader, times(1)).clearCache("file:///tmp/book.epub")
        verify(epubLoader, times(1)).clearCache("content://com.example.provider/epub-item")
        verify(pdfLoader, times(1)).clearCache("file:///tmp/chapter.pdf")
        verify(pdfLoader, times(1)).clearCache("content://com.example.provider/pdf-item")
        verify(webLoader, times(5)).clearCache(any())
    }

    @Test
    fun bulk_deletion_concurrency_is_capped_at_4() = runTest {
        val activeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrency = java.util.concurrent.atomic.AtomicInteger(0)

        whenever(webLoader.clearCache(any())).doAnswer {
            val current = activeCount.incrementAndGet()
            maxConcurrency.updateAndGet { maxOf(it, current) }
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(10) }
            activeCount.decrementAndGet()
            Unit
        }

        whenever(webLoader.clearDownload(any())).thenAnswer {
            Unit
        }

        val urls = (1..10).map { "https://example.com/item-$it" }

        val clearedCacheCount = repository.clearCachesForUrls(urls)
        assertEquals(10, clearedCacheCount)
        val max1 = maxConcurrency.getAndSet(0)
        assertTrue("Max concurrency for clearCachesForUrls was $max1, expected in 2..4", max1 in 2..4)

        val clearedBothCount = repository.clearCachesAndDownloadsForUrls(urls)
        assertEquals(10, clearedBothCount)
        val max2 = maxConcurrency.get()
        assertTrue("Max concurrency for clearCachesAndDownloadsForUrls was $max2, expected in 2..4", max2 in 2..4)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun bulk_deletion_clearCachesForUrls_rethrows_cancellation() = runTest {
        whenever(webLoader.clearCache(any())).thenAnswer {
            throw kotlinx.coroutines.CancellationException("Cancelled bulk delete")
        }
        repository.clearCachesForUrls(listOf("https://example.com/item-1"))
    }

    @Test
    fun contentUriEpubUsesMimeForPrefetchInspectAndContentType() = runTest {
        val contentUri = "content://com.example.provider/document/42"
        whenever(contentUriTypeResolver.resolveMimeType(any())).thenReturn("application/epub+zip")
        whenever(epubLoader.prefetchEpub(contentUri, StorageTier.DOWNLOADS)).thenReturn(true)
        whenever(epubLoader.isCached(contentUri)).thenReturn(true)

        val prefetch = repository.prefetch(contentUri, PrefetchMode.USER_REQUESTED)
        val inspect = repository.inspectCache(contentUri)

        assertEquals(ContentType.EPUB, repository.inferContentType(contentUri))
        assertTrue(prefetch.isComplete)
        assertTrue(inspect.isComplete)
        verify(epubLoader).prefetchEpub(contentUri, StorageTier.DOWNLOADS)
        verify(epubLoader).isCached(contentUri)
        verify(pdfLoader, never()).clearCache(any())
    }

    @Test
    fun getCacheSize_includes_loader_and_http_caches() = runTest {
        whenever(webLoader.getCacheSize()).thenReturn(11L)
        whenever(epubLoader.getCacheSize()).thenReturn(13L)
        whenever(pdfLoader.getCacheSize()).thenReturn(0L)
        whenever(okHttpCache.size()).thenReturn(17L)

        assertEquals(41L, repository.getCacheSize())
    }

    @Test
    fun clearAllCache_clears_loader_and_http_caches() = runTest {
        repository.clearAllCache()

        verify(webLoader).clearAllCache()
        verify(epubLoader).clearAllCache()
        verify(pdfLoader).clearAllCache()
        verify(okHttpCache).evictAll()
    }

    @Test
    fun webTimeoutReturnsBoundedError() = runTest {
        val url = "https://example.com/chapter-timeout"
        whenever(webLoader.loadWebContent(url)).thenThrow(createTimeoutCancellationException())

        val result = repository.loadContent(url)

        assertTrue(result is ContentResult.Error)
        assertEquals("Timed out loading chapter", (result as ContentResult.Error).message)
    }

    @Test
    fun resetWebLoadStateOnlyAffectsWebUrls() = runTest {
        val webUrl = "https://example.com/chapter-1"
        val localUrl = "file:///tmp/book.epub"

        repository.resetWebLoadState(webUrl, clearCachedHtml = true)
        repository.resetWebLoadState(localUrl, clearCachedHtml = true)

        verify(webLoader, times(1)).resetInFlightState(webUrl)
        verify(webLoader, times(1)).clearCachedHtml(webUrl)
        verify(webLoader, times(1)).resetInFlightState(any())
        verify(webLoader, times(1)).clearCachedHtml(any())
    }

    private fun createTimeoutCancellationException(): TimeoutCancellationException {
        val ctor = TimeoutCancellationException::class.java.getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance("boom")
    }
}
