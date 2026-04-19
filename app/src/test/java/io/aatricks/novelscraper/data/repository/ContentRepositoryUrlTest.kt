package io.aatricks.novelscraper.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.PrefetchMode
import io.aatricks.novelscraper.data.repository.content.EpubContentLoader
import io.aatricks.novelscraper.data.repository.content.LocalContentLoader
import io.aatricks.novelscraper.data.repository.content.PdfContentLoader
import io.aatricks.novelscraper.data.repository.content.ContentUriTypeResolver
import io.aatricks.novelscraper.data.repository.content.WebContentLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
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
        tempCacheDir = createTempDir(prefix = "content-repository-url-test")
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
    fun contentUriEpubUsesMimeForPrefetchInspectAndContentType() = runTest {
        val contentUri = "content://com.example.provider/document/42"
        whenever(contentUriTypeResolver.resolveMimeType(any())).thenReturn("application/epub+zip")
        whenever(epubLoader.prefetchEpub(contentUri)).thenReturn(true)
        whenever(epubLoader.isCached(contentUri)).thenReturn(true)

        val prefetch = repository.prefetch(contentUri, PrefetchMode.USER_REQUESTED)
        val inspect = repository.inspectCache(contentUri)

        assertEquals(ContentType.EPUB, repository.inferContentType(contentUri))
        assertTrue(prefetch.isComplete)
        assertTrue(inspect.isComplete)
        verify(epubLoader).prefetchEpub(contentUri)
        verify(epubLoader).isCached(contentUri)
        verify(pdfLoader, never()).clearCache(any())
    }

    @Test
    fun getCacheSize_includes_http_and_image_cache() = runTest {
        val imageCacheDir = File(tempCacheDir, "image_cache").apply { mkdirs() }
        File(imageCacheDir, "chapter.bin").writeBytes(ByteArray(7))

        whenever(webLoader.getCacheSize()).thenReturn(11L)
        whenever(epubLoader.getCacheSize()).thenReturn(13L)
        whenever(okHttpCache.size()).thenReturn(17L)

        assertEquals(48L, repository.getCacheSize())
    }

    @Test
    fun clearAllCache_clears_external_disk_caches() = runTest {
        val imageCacheDir = File(tempCacheDir, "image_cache").apply { mkdirs() }
        File(imageCacheDir, "chapter.bin").writeBytes(ByteArray(5))

        assertTrue(repository.clearAllCache())

        verify(webLoader).clearAllCache()
        verify(epubLoader).clearAllCache()
        verify(pdfLoader).clearAllCache()
        verify(okHttpCache).evictAll()
        assertTrue(imageCacheDir.exists())
        assertFalse(imageCacheDir.listFiles()?.isNotEmpty() == true)
    }
}
