package io.aatricks.easyreader.data.repository

import android.content.Context
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.content.ContentUriTypeResolver
import io.aatricks.easyreader.data.repository.content.EpubContentLoader
import io.aatricks.easyreader.data.repository.content.LocalContentLoader
import io.aatricks.easyreader.data.repository.content.PdfContentLoader
import io.aatricks.easyreader.data.repository.content.WebContentLoader
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ContentRepositoryCacheTrimTest {

    private lateinit var context: Context
    private lateinit var webLoader: WebContentLoader
    private lateinit var epubLoader: EpubContentLoader
    private lateinit var pdfLoader: PdfContentLoader
    private lateinit var localLoader: LocalContentLoader
    private lateinit var contentUriTypeResolver: ContentUriTypeResolver
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var repository: ContentRepository

    @Before
    fun setup() {
        context = mock<Context>()
        webLoader = mock<WebContentLoader>()
        epubLoader = mock<EpubContentLoader>()
        pdfLoader = mock<PdfContentLoader>()
        localLoader = mock<LocalContentLoader>()
        contentUriTypeResolver = mock<ContentUriTypeResolver>()
        okHttpClient = mock<OkHttpClient>()

        repository = ContentRepository(
            webLoader = webLoader,
            pdfLoader = pdfLoader,
            epubLoader = epubLoader,
            localLoader = localLoader,
            contentUriTypeResolver = contentUriTypeResolver,
            context = context,
            okHttpClient = okHttpClient
        )
    }

    @Test
    fun repeated_loadContent_uses_throttled_automatic_trim_and_explicit_trim_bypasses() = runTest {
        whenever(webLoader.loadWebContent(any())).thenReturn(ContentResult.Success(emptyList(), "Title", "https://example.com/ch1"))

        // Call loadContent twice in rapid succession
        repository.loadContent("https://example.com/ch1")
        repository.loadContent("https://example.com/ch2")

        // Should invoke trimCaches ONCE because second automatic request inside interval is throttled
        verify(webLoader, times(1)).trimCaches(any(), any())

        // Explicit forced trim bypasses throttling
        repository.trimCaches()
        verify(webLoader, times(2)).trimCaches(any(), any())
    }

    @Test
    fun prefetch_and_downloadChapter_use_throttled_automatic_trim() = runTest {
        whenever(webLoader.prefetch(any(), any())).thenReturn(
            PrefetchResult("https://example.com/ch1", htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true, isRetryable = false)
        )
        whenever(webLoader.downloadChapter(any(), any())).thenReturn(
            PrefetchResult("https://example.com/ch1", htmlCached = true, totalImages = 0, cachedImages = 0, isComplete = true, isRetryable = false)
        )

        // Reset lastCacheTrimAtMs timestamp by forcing a trim first
        repository.trimCaches()
        verify(webLoader, times(1)).trimCaches(any(), any())

        // Calling prefetch and downloadChapter within interval should be throttled
        repository.prefetch("https://example.com/ch1", PrefetchMode.USER_REQUESTED)
        repository.downloadChapter("https://example.com/ch1")

        // trimCaches should still have been called only once (the initial explicit trim)
        verify(webLoader, times(1)).trimCaches(any(), any())
    }
}
