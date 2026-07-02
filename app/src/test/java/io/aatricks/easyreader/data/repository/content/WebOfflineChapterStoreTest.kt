package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.util.CacheKeyUtils
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files

class WebOfflineChapterStoreTest {

    private fun createStore(
        htmlParser: HtmlParser,
        interceptor: Interceptor,
        tempDir: File
    ): Pair<WebOfflineChapterStore, InMemoryPermanentFailureStore> {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val imageDownloader = ImageDownloader(client)
        val mediaCacheDir = File(tempDir, "media_cache").apply { mkdirs() }
        val mediaDownloadsDir = File(tempDir, "media_downloads").apply { mkdirs() }
        val imageCache = ImageCache(mediaCacheDir, mediaDownloadsDir)
        val failureStore = InMemoryPermanentFailureStore()
        val webOfflineDir = File(tempDir, "web_offline").apply { mkdirs() }
        val store = WebOfflineChapterStore(
            webOfflineDir,
            htmlParser,
            imageDownloader,
            imageCache,
            failureStore
        )
        return Pair(store, failureStore)
    }

    private fun buildResponse(
        request: okhttp3.Request,
        body: String,
        contentType: String,
        code: Int = 200
    ): Response {
        val payload = if (code == 200 && contentType.startsWith("image/")) {
            val bodyBytes = body.toByteArray()
            val header = VALID_JPEG_HEADER + bodyBytes
            val padded = if (header.size < 62) header + ByteArray(62 - header.size) else header
            padded + JPEG_EOI
        } else {
            body.toByteArray()
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(payload.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun tinyPng(width: Int, height: Int): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val ihdrData = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8)
            .put(2)
            .put(0)
            .put(0)
            .put(0)
            .array()
        return signature +
            pngChunk("IHDR", ihdrData) +
            pngChunk("IEND", byteArrayOf())
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + data.size + 4)
            .putInt(data.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(data)
            .putInt(0)
            .array()
    }

    @Test
    fun `zero-image text chapter downloads to complete persistent manifest`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/novel"
        val htmlParser = mock<HtmlParser>()
        val (store, _) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                buildResponse(chain.request(), "<html><body><p>Novel text</p></body></html>", "text/html")
            },
            tempDir = tempDir
        )

        val doc = Jsoup.parse("<html><body><p>Novel text</p></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Text("Novel text")))

        val result = store.downloadChapter(chapterUrl, doc, null)

        assertTrue(result.isComplete)
        assertTrue(result.isPersistentDownload)
        assertFalse(result.isRetryable)
        assertEquals(0, result.totalImages)

        val inspectResult = store.inspect(chapterUrl)
        assertTrue(inspectResult.isComplete)
        assertTrue(inspectResult.isPersistentDownload)
        assertFalse(inspectResult.isRetryable)

        val content = store.loadContent(chapterUrl)
        assertTrue(content != null)
        assertEquals("Novel text", (content!!.elements.first() as ContentElement.Text).content)
        assertTrue(store.hasCompleteChapter(chapterUrl))
    }

    @Test
    fun `zero-image shell with manga reader hints is not marked complete`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/manga-shell"
        val htmlParser = mock<HtmlParser>()
        val (store, _) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                buildResponse(chain.request(), "<html><body><div class=\"reader-content\"></div></body></html>", "text/html")
            },
            tempDir = tempDir
        )

        val doc = Jsoup.parse("<html><body><div class=\"reader-content\"></div></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(emptyList())

        val result = store.downloadChapter(chapterUrl, doc, null)

        assertFalse(result.isComplete)
        assertFalse(result.isPersistentDownload)
        assertTrue(result.isRetryable)
        assertFalse(store.hasCompleteChapter(chapterUrl))
    }

    @Test
    fun `bad refetch does not clear an existing complete download`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/refetch"
        val htmlParser = mock<HtmlParser>()
        val (store, _) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                buildResponse(chain.request(), "<html><body><p>Good content</p></body></html>", "text/html")
            },
            tempDir = tempDir
        )

        // First, download successfully
        val goodDoc = Jsoup.parse("<html><body><p>Good content</p></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Text("Good content")))
        val firstResult = store.downloadChapter(chapterUrl, goodDoc, null)
        assertTrue(firstResult.isComplete)

        // Now, download again with a shell
        val badDoc = Jsoup.parse("<html><body><div class=\"reader-content\"></div></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(emptyList())
        val secondResult = store.downloadChapter(chapterUrl, badDoc, null)

        // It should still be complete and have hasCompleteChapter = true
        assertTrue(store.hasCompleteChapter(chapterUrl))
        val inspectResult = store.inspect(chapterUrl)
        assertTrue(inspectResult.isComplete)
    }

    @Test
    fun `permanent image failure is recorded and inspect becomes terminal`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/chapter-images"
        val imageUrl1 = "https://example.com/img1.jpg"
        val imageUrl2 = "https://example.com/img2.jpg"
        val htmlParser = mock<HtmlParser>()
        val (store, failureStore) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    imageUrl1 -> buildResponse(request, "img1", "image/jpeg")
                    imageUrl2 -> buildResponse(request, "Not Found", "text/plain", code = 404)
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            },
            tempDir = tempDir
        )

        val doc = Jsoup.parse("<html><body><img src=\"$imageUrl1\"/><img src=\"$imageUrl2\"/></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(ContentElement.Image(imageUrl1), ContentElement.Image(imageUrl2))
        )

        val result = store.downloadChapter(chapterUrl, doc, null)

        assertTrue(result.isComplete) // 404 is accounted for!
        assertTrue(result.hasPermanentFailures)
        assertFalse(result.isRetryable)
        assertEquals(1, result.cachedImages)
        assertEquals(2, result.totalImages)

        val failedUrls = failureStore.load(chapterUrl, 0L)
        assertTrue(imageUrl2 in failedUrls)
        assertFalse(imageUrl1 in failedUrls)

        // manifest is NOT complete on disk
        assertNull(store.loadContent(chapterUrl))
    }

    @Test
    fun `retryable network failure stays incomplete and records nothing`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/chapter-retryable"
        val imageUrl = "https://example.com/img.jpg"
        val htmlParser = mock<HtmlParser>()
        val (store, failureStore) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                throw IOException("Simulated network failure")
            },
            tempDir = tempDir
        )

        val doc = Jsoup.parse("<html><body><img src=\"$imageUrl\"/></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(listOf(ContentElement.Image(imageUrl)))

        val result = store.downloadChapter(chapterUrl, doc, null)

        assertFalse(result.isComplete)
        assertTrue(result.isRetryable)
        assertFalse(result.hasPermanentFailures)
        assertTrue(failureStore.load(chapterUrl, 0L).isEmpty())
    }

    @Test
    fun `inspect accounts fresh permanent failures toward completion but not cachedImages`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/inspect-permanent"
        val imageUrl1 = "https://example.com/img1.jpg"
        val imageUrl2 = "https://example.com/img2.jpg"
        val htmlParser = mock<HtmlParser>()
        val (store, failureStore) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    imageUrl1 -> buildResponse(request, "img1", "image/jpeg")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            },
            tempDir = tempDir
        )

        // Pre-record failure
        failureStore.record(chapterUrl, listOf(imageUrl2), System.currentTimeMillis())

        val doc = Jsoup.parse("<html><body><img src=\"$imageUrl1\"/><img src=\"$imageUrl2\"/></body></html>", chapterUrl)
        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(ContentElement.Image(imageUrl1), ContentElement.Image(imageUrl2))
        )

        // First download, imageUrl1 succeeds, imageUrl2 fails (which is pre-recorded)
        val result = store.downloadChapter(chapterUrl, doc, null)
        assertTrue(result.isComplete)
        assertEquals(1, result.cachedImages)
        assertTrue(result.hasPermanentFailures)
    }

    @Test
    fun `inspect of legacy manifest with images behaves as before`() = runBlocking {
        val tempDir = Files.createTempDirectory("offline-store-test").toFile()
        val chapterUrl = "https://example.com/legacy"
        val htmlParser = mock<HtmlParser>()
        val (store, _) = createStore(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                buildResponse(chain.request(), "", "text/plain", code = 404)
            },
            tempDir = tempDir
        )

        val chapterKey = CacheKeyUtils.keyFor(chapterUrl)
        val offlineDir = File(tempDir, "web_offline")
        val chapterDir = File(offlineDir, chapterKey)
        chapterDir.mkdirs()

        // Write image file on disk
        val imageDir = File(chapterDir, "images")
        imageDir.mkdirs()
        val imageFile = File(imageDir, "img1.jpg")
        imageFile.writeBytes(tinyPng(2, 2))

        // Write legacy manifest.json
        val manifestFile = File(chapterDir, "manifest.json")
        val manifestContent = """
            {
              "schemaVersion": 1,
              "chapterUrl": "$chapterUrl",
              "title": "Legacy Chapter",
              "elements": [],
              "images": [
                {
                  "url": "https://example.com/img1.jpg",
                  "fileName": "img1.jpg",
                  "width": 2,
                  "height": 2,
                  "bytes": ${imageFile.length()}
                }
              ],
              "complete": true,
              "downloadedAtMs": 1234567890
            }
        """.trimIndent()
        manifestFile.writeText(manifestContent)

        val inspectResult = store.inspect(chapterUrl)
        assertTrue(inspectResult.isComplete)
        assertFalse(inspectResult.hasPermanentFailures)
        assertFalse(inspectResult.isRetryable)
        assertEquals(1, inspectResult.cachedImages)
    }

    private companion object {
        private val VALID_JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
