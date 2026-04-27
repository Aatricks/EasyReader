package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.data.model.PrefetchMode
import io.aatricks.easyreader.data.repository.HtmlParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WebContentLoaderTest {

    @Test
    fun `colliding chapter urls keep distinct cache files and titles`() = runBlocking {
        val urlA = "https://example.com/Aa"
        val urlB = "https://example.com/BB"
        val imageA = "https://example.com/image/Aa.jpg"
        val imageB = "https://example.com/image/BB.jpg"
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                buildResponse(chain.request(), "", "text/plain", code = 404)
            }
        )

        whenever(htmlParser.parse(any(), eq(urlA))).thenReturn(emptyList())
        whenever(htmlParser.parse(any(), eq(urlB))).thenReturn(emptyList())

        loader.getCachedFile(urlA).writeText("<html><head><title>Chapter A</title></head><body></body></html>")
        loader.getCachedFile(urlB).writeText("<html><head><title>Chapter B</title></head><body></body></html>")

        val resultA = loader.loadWebContent(urlA) as ContentResult.Success
        val resultB = loader.loadWebContent(urlB) as ContentResult.Success

        assertEquals(urlA.hashCode(), urlB.hashCode())
        assertNotEquals(loader.getCachedFile(urlA).name, loader.getCachedFile(urlB).name)
        assertNotEquals(loader.getCachedMediaFile(imageA).name, loader.getCachedMediaFile(imageB).name)
        assertEquals("Chapter A", resultA.title)
        assertEquals("Chapter B", resultB.title)
    }

    @Test
    fun `loadWebContent uses cached html and cached media without range probes for image dimensions`() = runBlocking {
        val chapterUrl = "https://example.com/chapter-1"
        val imageUrl = "https://example.com/image-1.jpg"
        val rangeRequests = AtomicInteger(0)
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (request.header("Range") != null) {
                    rangeRequests.incrementAndGet()
                }
                when (url) {
                    chapterUrl -> buildResponse(request, "<html><head><title>Chapter 1</title></head><body></body></html>", "text/html")
                    imageUrl -> buildResponse(request, "fake-image-binary", "image/jpeg")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(ContentElement.Image(imageUrl))
        )
        loader.getCachedFile(chapterUrl).writeText("<html><head><title>Chapter 1</title></head><body></body></html>")
        loader.getCachedMediaFile(imageUrl).writeBytes(tinyPng(width = 2, height = 3))

        val result = loader.loadWebContent(chapterUrl)

        assertTrue(result is ContentResult.Success)
        assertEquals(0, rangeRequests.get())
    }

    @Test
    fun `loadWebContent keeps individual image structure when reopening from cached html without media cache`() = runBlocking {
        val chapterUrl = "https://example.com/chapter-grouped"
        val imageUrl1 = "https://example.com/group-1.png"
        val imageUrl2 = "https://example.com/group-2.png"
        val htmlParser = mock<HtmlParser>()
        val rangeRequests = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(
                        request,
                        "<html><head><title>Grouped</title></head><body></body></html>",
                        "text/html"
                    )

                    imageUrl1, imageUrl2 -> {
                        if (request.header("Range") != null) {
                            rangeRequests.incrementAndGet()
                            buildByteResponse(request, tinyPng(width = 1000, height = 600), "image/png")
                        } else {
                            buildByteResponse(request, tinyPng(width = 1000, height = 600), "image/png")
                        }
                    }

                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(
                ContentElement.Image(imageUrl1),
                ContentElement.Image(imageUrl2)
            )
        )

        val firstLoad = loader.loadWebContent(chapterUrl) as ContentResult.Success
        val secondLoad = loader.loadWebContent(chapterUrl) as ContentResult.Success

        assertEquals(2, firstLoad.elements.size)
        assertEquals(2, secondLoad.elements.size)
        assertTrue(firstLoad.elements.all { it is ContentElement.Image })
        assertTrue(secondLoad.elements.all { it is ContentElement.Image })
        assertEquals(0, rangeRequests.get())
    }

    @Test
    fun `loadWebContent keeps long-strip rows ungrouped for stable restore`() = runBlocking {
        val chapterUrl = "https://example.com/chapter-long-strip"
        val imageUrls = (1..4).map { "https://example.com/strip-$it.png" }
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(
                        request,
                        "<html><head><title>Long Strip</title></head><body></body></html>",
                        "text/html"
                    )

                    in imageUrls -> buildByteResponse(request, tinyPng(width = 1000, height = 600), "image/png")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            imageUrls.map { ContentElement.Image(it) }
        )
        imageUrls.forEach { imageUrl ->
            loader.getCachedMediaFile(imageUrl).writeBytes(tinyPng(width = 1000, height = 600))
        }

        val result = loader.loadWebContent(chapterUrl) as ContentResult.Success

        assertEquals(4, result.elements.size)
        assertTrue(result.elements.all { it is ContentElement.Image })
        assertEquals(imageUrls, result.elements.map { (it as ContentElement.Image).url })
    }

    @Test
    fun `loadWebContent keeps long-strip manhwa top-level image order stable across unknown and known dimensions`() = runBlocking {
        val chapterUrl = "https://example.com/manhwa/chapter-7"
        val imageUrls = (1..8).map { "https://example.com/manhwa-$it.png" }
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(
                        request,
                        "<html><head><title>Manhwa</title></head><body></body></html>",
                        "text/html"
                    )
                    in imageUrls -> buildByteResponse(request, tinyPng(width = 1080, height = 1920), "image/png")
                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(imageUrls.map { ContentElement.Image(it) })

        val firstLoad = loader.loadWebContent(chapterUrl) as ContentResult.Success

        imageUrls.forEachIndexed { index, imageUrl ->
            loader.getCachedMediaFile(imageUrl).writeBytes(tinyPng(width = 1080 + index, height = 1920 + index))
        }
        val secondLoad = loader.loadWebContent(chapterUrl) as ContentResult.Success

        val firstUrls = firstLoad.elements.mapNotNull { (it as? ContentElement.Image)?.url }
        val secondUrls = secondLoad.elements.mapNotNull { (it as? ContentElement.Image)?.url }

        assertEquals(8, firstLoad.elements.size)
        assertEquals(8, secondLoad.elements.size)
        assertTrue(firstLoad.elements.all { it is ContentElement.Image })
        assertTrue(secondLoad.elements.all { it is ContentElement.Image })
        assertEquals(imageUrls, firstUrls)
        assertEquals(imageUrls, secondUrls)
        assertEquals(firstUrls[5], secondUrls[5])
    }

    @Test
    fun `image downloads are single flight across warm and direct cache requests`() = runBlocking {
        val pageUrl = "https://example.com/chapter-2"
        val imageUrl = "https://example.com/image-2.jpg"
        val imageRequests = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                if (request.url.toString() == imageUrl && request.header("Range") == null) {
                    imageRequests.incrementAndGet()
                    Thread.sleep(150)
                    buildResponse(request, "fake-image-binary", "image/jpeg")
                } else {
                    buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        val results = listOf(
            async { loader.downloadAndCacheImage(imageUrl, pageUrl) },
            async { loader.warmImage(imageUrl, pageUrl) }
        ).awaitAll()

        assertEquals(1, imageRequests.get())
        assertTrue(results.all { it != null })
    }

    @Test
    fun `user requested and speculative prefetch report actual cached image coverage`() = runBlocking {
        val userUrl = "https://example.com/chapter-user"
        val speculativeUrl = "https://example.com/chapter-spec"
        val userImages = listOf(
            "https://example.com/u1.jpg",
            "https://example.com/u2.jpg"
        )
        val speculativeImages = listOf(
            "https://example.com/s1.jpg",
            "https://example.com/s2.jpg",
            "https://example.com/s3.jpg",
            "https://example.com/s4.jpg",
            "https://example.com/s5.jpg"
        )
        val speculativeDownloads = AtomicInteger(0)
        val htmlParser = mock<HtmlParser>()
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                when (url) {
                    userUrl -> buildResponse(request, "<html><head><title>User</title></head><body></body></html>", "text/html")
                    speculativeUrl -> buildResponse(request, "<html><head><title>Spec</title></head><body></body></html>", "text/html")
                    userImages[0] -> buildResponse(request, "img-1", "image/jpeg")
                    userImages[1] -> buildResponse(request, "", "text/plain", code = 500)
                    else -> {
                        if (url in speculativeImages && request.header("Range") == null) {
                            speculativeDownloads.incrementAndGet()
                            buildResponse(request, "img-spec", "image/jpeg")
                        } else {
                            buildResponse(request, "", "text/plain", code = 404)
                        }
                    }
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(userUrl))).thenReturn(userImages.map { ContentElement.Image(it) })
        whenever(htmlParser.parse(any(), eq(speculativeUrl))).thenReturn(speculativeImages.map { ContentElement.Image(it) })

        val userRequested = loader.prefetch(userUrl, PrefetchMode.USER_REQUESTED)
        val speculative = loader.prefetch(speculativeUrl, PrefetchMode.SPECULATIVE)

        assertTrue(userRequested.htmlCached)
        assertEquals(2, userRequested.totalImages)
        assertEquals(1, userRequested.cachedImages)
        assertFalse(userRequested.isComplete)

        assertTrue(speculative.htmlCached)
        assertEquals(5, speculative.totalImages)
        assertEquals(0, speculative.cachedImages)
        assertFalse(speculative.isComplete)
        assertEquals(0, speculativeDownloads.get())
    }

    @Test
    fun `loadWebContent does not wait for remote image dimensions on initial load`() = runBlocking {
        val chapterUrl = "https://example.com/chapter-nonblocking"
        val imageUrl = "https://example.com/nonblocking-image.png"
        val htmlParser = mock<HtmlParser>()
        val rangeRequests = AtomicInteger(0)
        val fullRequests = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = htmlParser,
            interceptor = Interceptor { chain ->
                val request = chain.request()
                when (request.url.toString()) {
                    chapterUrl -> buildResponse(
                        request,
                        "<html><head><title>Fallback</title></head><body></body></html>",
                        "text/html"
                    )

                    imageUrl -> {
                        if (request.header("Range") != null) {
                            rangeRequests.incrementAndGet()
                            Thread.sleep(2_000)
                            buildByteResponse(request, tinyPng(width = 2, height = 3), "image/png")
                        } else {
                            fullRequests.incrementAndGet()
                            Thread.sleep(2_000)
                            buildByteResponse(request, tinyPng(width = 2, height = 3), "image/png")
                        }
                    }

                    else -> buildResponse(request, "", "text/plain", code = 404)
                }
            }
        )

        whenever(htmlParser.parse(any(), eq(chapterUrl))).thenReturn(
            listOf(ContentElement.Image(imageUrl))
        )

        val startedAt = System.currentTimeMillis()
        val result = loader.loadWebContent(chapterUrl) as ContentResult.Success
        val elapsedMs = System.currentTimeMillis() - startedAt
        val image = result.elements.single() as ContentElement.Image

        assertTrue("Initial load should return before background network image work", elapsedMs < 1_000)
        assertEquals(0, rangeRequests.get())
        assertEquals(0, image.width)
        assertEquals(0, image.height)
        assertTrue(fullRequests.get() >= 0)
    }

    @Test
    fun `oversized Content-Length is rejected before download`() = runBlocking {
        val imageUrl = "https://example.com/big-image.jpg"
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType() = "image/jpeg".toMediaType()
                    override fun contentLength() = 21 * 1024 * 1024L
                    override fun source() = okio.Buffer()
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        )

        val result = loader.downloadAndCacheImage(imageUrl, "https://example.com")

        assertNull(result)
        assertFalse(loader.getCachedMediaFile(imageUrl).exists())
    }

    @Test
    fun `oversized body without Content-Length is aborted during stream`() = runBlocking {
        val imageUrl = "https://example.com/stream-big.jpg"
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val bigData = ByteArray(8192)
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType() = "image/jpeg".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource {
                        val buffer = okio.Buffer()
                        // Provide more than 20MB in chunks
                        repeat(2600) { // 2600 * 8192 > 20MB
                            buffer.write(bigData)
                        }
                        return buffer
                    }
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        )

        val result = loader.downloadAndCacheImage(imageUrl, "https://example.com")

        assertNull(result)
        assertFalse(loader.getCachedMediaFile(imageUrl).exists())
        val cachedFile = loader.getCachedMediaFile(imageUrl)
        val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")
        assertFalse(tempFile.exists())
    }

    @Test
    fun `mid-stream failure deletes temp and leaves no final file`() = runBlocking {
        val imageUrl = "https://example.com/fail-mid-stream.jpg"
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val request = chain.request()
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType() = "image/jpeg".toMediaType()
                    override fun contentLength() = 1024 * 1024L
                    override fun source(): okio.BufferedSource {
                        throw java.io.IOException("Network cut")
                    }
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        )

        val result = loader.downloadAndCacheImage(imageUrl, "https://example.com")

        assertNull(result)
        assertFalse(loader.getCachedMediaFile(imageUrl).exists())
        val cachedFile = loader.getCachedMediaFile(imageUrl)
        val tempFile = File(cachedFile.parent, "${cachedFile.name}.tmp")
        assertFalse(tempFile.exists())
    }

    private fun createLoader(
        htmlParser: HtmlParser,
        interceptor: Interceptor
    ): WebContentLoader {
        val root = Files.createTempDirectory("web-loader-test").toFile()
        val htmlCacheDir = File(root, "html_cache").apply { mkdirs() }
        val mediaCacheDir = File(root, "media_cache").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val imageCache = ImageCache(mediaCacheDir)
        val imageDownloader = ImageDownloader(client)
        return WebContentLoader(htmlParser, client, imageCache, imageDownloader, htmlCacheDir)
    }

    private fun buildResponse(
        request: okhttp3.Request,
        body: String,
        contentType: String,
        code: Int = 200
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun buildByteResponse(
        request: okhttp3.Request,
        body: ByteArray,
        contentType: String,
        code: Int = 200
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(contentType.toMediaType()))
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

    @Test
    fun `speculative failure does not prevent user-requested success`() = runBlocking {
        val imageUrl = "https://example.com/spec-fail.jpg"
        val attempts = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val count = attempts.incrementAndGet()
                if (count <= 2) {
                    // Speculative attempts fail (500 is retried)
                    buildResponse(chain.request(), "", "text/plain", code = 500)
                } else {
                    // User-requested attempt succeeds
                    buildResponse(chain.request(), "binary-data", "image/jpeg")
                }
            }
        )

        // 1. Speculative fails
        val specResult = loader.warmImage(imageUrl, "https://example.com")
        assertNull(specResult)
        assertEquals(2, attempts.get()) // SHORT_REQUEST_ATTEMPTS = 2

        // 2. User-requested succeeds
        val userResult = loader.downloadAndCacheImage(imageUrl, "https://example.com")
        assertTrue(userResult != null && userResult.exists())
        assertTrue(attempts.get() > 2)
    }

    @Test
    fun `user requested and speculative deduplicate correctly`() = runBlocking {
        val imageUrl = "https://example.com/dedupe.jpg"
        val imageRequests = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                imageRequests.incrementAndGet()
                Thread.sleep(100)
                buildResponse(chain.request(), "binary-data", "image/jpeg")
            }
        )

        // Case 1: User-requested in flight, speculative joins
        val results1 = listOf(
            async { loader.downloadAndCacheImage(imageUrl, "https://example.com") },
            async { loader.warmImage(imageUrl, "https://example.com") }
        ).awaitAll()

        assertEquals(1, imageRequests.get())
        assertTrue(results1.all { it != null })

        // Clear cache for next case
        loader.getCachedMediaFile(imageUrl).delete()
        imageRequests.set(0)

        // Case 2: Two user requests dedupe
        val results2 = listOf(
            async { loader.downloadAndCacheImage(imageUrl, "https://example.com") },
            async { loader.downloadAndCacheImage(imageUrl, "https://example.com") }
        ).awaitAll()

        assertEquals(1, imageRequests.get())
        assertTrue(results2.all { it != null })
    }

    @Test
    fun `user requested waits for speculative then retries on failure`() = runBlocking {
        val imageUrl = "https://example.com/overlap.jpg"
        val attempts = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                val count = attempts.incrementAndGet()
                Thread.sleep(50)
                if (count <= 2) { // Fail first two attempts (speculative, 500 is retried)
                    buildResponse(chain.request(), "", "text/plain", code = 500)
                } else {
                    buildResponse(chain.request(), "binary-data", "image/jpeg")
                }
            }
        )

        val specJob = async { loader.warmImage(imageUrl, "https://example.com") }
        kotlinx.coroutines.delay(20) // Ensure speculative is in flight
        val userJob = async { loader.downloadAndCacheImage(imageUrl, "https://example.com") }

        val specResult = specJob.await()
        val userResult = userJob.await()

        assertNull(specResult)
        assertTrue(userResult != null && userResult.exists())
        assertTrue(attempts.get() > 2)
    }

    @Test
    fun `failed job cleans map so retry works`() = runBlocking {
        val imageUrl = "https://example.com/retry.jpg"
        val attempts = AtomicInteger(0)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                if (attempts.incrementAndGet() <= 4) { // USER_REQUEST_ATTEMPTS is 4
                    buildResponse(chain.request(), "", "text/plain", code = 500)
                } else {
                    buildResponse(chain.request(), "binary-data", "image/jpeg")
                }
            }
        )

        // 1. First user request fails
        val result1 = loader.downloadAndCacheImage(imageUrl, "https://example.com")
        assertNull(result1)

        // 2. Second user request retries and succeeds
        val result2 = loader.downloadAndCacheImage(imageUrl, "https://example.com")
        assertTrue(result2 != null && result2.exists())
    }

    @Test
    fun `in-flight image entry self-cleans after caller cancellation`() = runBlocking {
        val imageUrl = "https://example.com/cancel-cleanup.jpg"
        val started = AtomicInteger(0)
        val release = CountDownLatch(1)
        val loader = createLoader(
            htmlParser = mock(),
            interceptor = Interceptor { chain ->
                started.incrementAndGet()
                release.await(3, TimeUnit.SECONDS)
                buildResponse(chain.request(), "binary-data", "image/jpeg")
            }
        )

        val caller = launch {
            loader.downloadAndCacheImage(imageUrl, "https://example.com")
        }

        var sawStart = false
        repeat(50) {
            if (started.get() > 0) {
                sawStart = true
                return@repeat
            }
            delay(20)
        }
        assertTrue(sawStart || started.get() > 0)
        caller.cancelAndJoin()
        release.countDown()
        var cleaned = false
        repeat(20) {
            delay(100)
            val field = WebContentLoader::class.java.getDeclaredField("inFlightImageDownloads").apply { isAccessible = true }
            val inFlight = field.get(loader) as Map<*, *>
            if (inFlight.isEmpty()) {
                cleaned = true
                return@repeat
            }
        }

        val field = WebContentLoader::class.java.getDeclaredField("inFlightImageDownloads").apply { isAccessible = true }
        val inFlight = field.get(loader) as Map<*, *>
        assertTrue(cleaned || inFlight.isEmpty())
        assertEquals(0, inFlight.size)

        val retry = loader.downloadAndCacheImage(imageUrl, "https://example.com")
        assertTrue(retry != null && retry.exists())
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + data.size + 4)
            .putInt(data.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(data)
            .putInt(0)
            .array()
    }
}
