package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ImageRequestPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

class ImageDownloaderTest {
    companion object {
        private const val MAX_DIMENSION_SNIFF_BYTES = 64 * 1024L
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `executeImageRequest successfully downloads image`() = runBlocking {
        val imageUrl = "https://example.com/image.jpg"
        val pageUrl = "https://example.com/page"
        val destFile = tempFolder.newFile("test_image.jpg")
        val content = "fake-image-binary"
        
        val client = createClient { chain ->
            buildResponse(chain.request(), content, "image/jpeg")
        }
        val downloader = ImageDownloader(client)
        
        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = pageUrl,
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = destFile
        )
        
        assertTrue(result is ImageFetchResult.Success)
        assertEquals(destFile.absolutePath, (result as ImageFetchResult.Success).file.absolutePath)
        assertEquals(content, destFile.readText())
    }

    @Test
    fun `executeImageRequest uses MangaBat root referer for MangaBat CDN URLs`() = runBlocking {
        val imageUrl = "https://img-r1.2xstorage.com/mercenary-enrollment/238/0.webp"
        val refererHeader = AtomicReference<String?>()

        val client = createClient { chain ->
            refererHeader.set(chain.request().header("Referer"))
            buildResponse(chain.request(), "fake-image-binary", "image/webp")
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = imageUrl,
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = tempFolder.newFile("mangabat.webp")
        )

        assertTrue(result is ImageFetchResult.Success)
        assertEquals("https://www.mangabats.com/", refererHeader.get())
    }

    @Test
    fun `MangaBat alternate image hosts use MangaBat root referer`() {
        val downloader = ImageDownloader(createClient { chain ->
            buildResponse(chain.request(), "unused", "text/plain")
        })

        assertEquals(
            "https://www.mangabats.com/",
            downloader.getReferer("https://imgs-2.2xstorage.com/thumb/versatile-mage.webp")
        )
        assertEquals(
            "https://www.mangabats.com/",
            downloader.getReferer("https://storage4.waitst.com/thumb/for-my-husbands-new-wife.webp")
        )
    }

    @Test
    fun `executeImageRequest handles HTTP error and retries`() = runBlocking {
        val imageUrl = "https://example.com/image.jpg"
        val attempts = AtomicInteger(0)
        
        val client = createClient { chain ->
            val count = attempts.incrementAndGet()
            if (count == 1) {
                buildResponse(chain.request(), "", "text/plain", code = 500)
            } else {
                buildResponse(chain.request(), "success", "image/jpeg")
            }
        }
        val downloader = ImageDownloader(client)
        
        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = tempFolder.newFile("retry.jpg")
        )
        
        assertTrue(result is ImageFetchResult.Success)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `executeImageRequest rejects too large images`() = runBlocking {
        val imageUrl = "https://example.com/large.jpg"
        
        val client = createClient { chain ->
            val body = "a".repeat(21 * 1024 * 1024).toResponseBody("image/jpeg".toMediaType())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)
        
        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = tempFolder.newFile("too_large.jpg")
        )
        
        assertTrue(result is ImageFetchResult.TooLarge)
    }

    @Test
    fun `executeImageRequest lightly spaces successful same-host requests`() = runBlocking {
        val imageUrl = "https://example.com/image.jpg"
        val requests = AtomicInteger(0)
        val timestamps = mutableListOf<Long>()
        
        val client = createClient { chain ->
            requests.incrementAndGet()
            timestamps.add(System.currentTimeMillis())
            buildResponse(chain.request(), "data", "image/jpeg")
        }
        val downloader = ImageDownloader(client)
        
        // Execute two requests to the same host
        downloader.executeImageRequest(imageUrl, "page", ImageRequestPriority.USER_REQUESTED)
        downloader.executeImageRequest(imageUrl, "page", ImageRequestPriority.USER_REQUESTED)
        
        assertEquals(2, requests.get())
        assertTrue(timestamps[1] - timestamps[0] < 250)
    }

    @Test
    fun `same host requests can overlap within concurrency limit`() = runBlocking {
        val started = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val release = CountDownLatch(1)
        val client = createClient { chain ->
            started.incrementAndGet()
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
            release.await(3, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            buildResponse(chain.request(), "data", "image/jpeg")
        }
        val downloader = ImageDownloader(client)

        val requests = (1..4).map { index ->
            async {
                withContext(Dispatchers.IO) {
                    downloader.executeImageRequest(
                        imageUrl = "https://example.com/image-$index.jpg",
                        pageUrl = "https://example.com/page",
                        priority = ImageRequestPriority.USER_REQUESTED
                    )
                }
            }
        }

        repeat(20) {
            if (started.get() >= 2) return@repeat
            delay(25)
        }
        release.countDown()
        requests.awaitAll()

        assertTrue(maxInFlight.get() in 2..4)
    }

    @Test
    fun `host throttle state is capped`() = runBlocking {
        val client = createClient { chain ->
            buildResponse(chain.request(), "data", "image/jpeg")
        }
        val downloader = ImageDownloader(client)

        repeat(300) { index ->
            downloader.executeImageRequest(
                imageUrl = "https://host-$index.example/image.jpg",
                pageUrl = "https://host-$index.example/page",
                priority = ImageRequestPriority.SPECULATIVE
            )
        }

        val field = ImageDownloader::class.java.getDeclaredField("hostThrottleStates").apply {
            isAccessible = true
        }
        val states = field.get(downloader) as Map<*, *>
        assertTrue(states.size <= 256)
    }

    @Test
    fun `Retry-After header is clamped to max host throttle`() = runBlocking {
        val imageUrl = "https://example.com/rate-limited.jpg"
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("Bad Request")
                .addHeader("Retry-After", "3600")
                .body("error".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.USER_REQUESTED
        )

        assertTrue(result is ImageFetchResult.HttpError)
        assertEquals(ImageDownloader.MAX_HOST_THROTTLE_MS, (result as ImageFetchResult.HttpError).retryAfterMs)
    }

    @Test
    fun `dimension sniff reads across partial chunks until EOF and parses bounds`() = runBlocking {
        val imageUrl = "https://example.com/chunked.png"
        val pngBytes = tinyPngHeader(width = 320, height = 240)
        val readCalls = AtomicInteger(0)
        val client = createClient { chain ->
            val body = ChunkedResponseBody(
                data = pngBytes,
                chunkSize = 3,
                contentLength = -1L,
                readCalls = readCalls
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.SPECULATIVE
        )

        assertTrue(result is ImageFetchResult.BoundedSuccess)
        val bytes = (result as ImageFetchResult.BoundedSuccess).bytes
        assertEquals(320 to 240, ImageBoundsParser.parse(bytes))
        assertTrue(readCalls.get() > 1)
    }

    @Test
    fun `dimension sniff never reads beyond max sniff plus one byte`() = runBlocking {
        val imageUrl = "https://example.com/large-chunked.bin"
        val sniffLimit = MAX_DIMENSION_SNIFF_BYTES + 1L
        val data = ByteArray((sniffLimit + 500).toInt()) { 0x7F.toByte() }
        val sourceBuffer = Buffer().write(data)
        val client = createClient { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = sourceBuffer
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.SPECULATIVE
        )

        assertTrue(result is ImageFetchResult.TooLarge)
        val consumed = data.size.toLong() - sourceBuffer.size
        assertEquals(sniffLimit, consumed)
    }

    @Test
    fun `dimension sniff handles EOF before limit deterministically`() = runBlocking {
        val imageUrl = "https://example.com/truncated.bin"
        val truncated = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        val client = createClient { chain ->
            val body = ChunkedResponseBody(
                data = truncated,
                chunkSize = 2,
                contentLength = -1L
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.SPECULATIVE
        )

        assertTrue(result is ImageFetchResult.BoundedSuccess)
        assertNull(ImageBoundsParser.parse((result as ImageFetchResult.BoundedSuccess).bytes))
    }

    @Test
    fun `dimension sniff keeps unsupported image behavior`() = runBlocking {
        val imageUrl = "https://example.com/unsupported.bin"
        val unsupported = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)
        val client = createClient { chain ->
            val body = ChunkedResponseBody(
                data = unsupported,
                chunkSize = 1,
                contentLength = -1L
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.SPECULATIVE
        )

        assertTrue(result is ImageFetchResult.BoundedSuccess)
        assertNull(ImageBoundsParser.parse((result as ImageFetchResult.BoundedSuccess).bytes))
    }

    @Test
    fun `full download path continues streaming to disk`() = runBlocking {
        val imageUrl = "https://example.com/streamed.jpg"
        val payload = ByteArray(25_000) { (it % 251).toByte() }
        val readCalls = AtomicInteger(0)
        val destFile = tempFolder.newFile("streamed.jpg")
        val client = createClient { chain ->
            val body = ChunkedResponseBody(
                data = payload,
                chunkSize = 2048,
                contentLength = -1L,
                readCalls = readCalls
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        val downloader = ImageDownloader(client)

        val result = downloader.executeImageRequest(
            imageUrl = imageUrl,
            pageUrl = "https://example.com",
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = destFile
        )

        assertTrue(result is ImageFetchResult.Success)
        assertEquals(payload.size.toLong(), destFile.length())
        assertTrue(readCalls.get() > 1)
    }

    private fun createClient(interceptor: Interceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
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

    private fun tinyPngHeader(width: Int, height: Int): ByteArray {
        val header = ByteArray(24)
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        signature.copyInto(header, 0)
        ByteBuffer.allocate(4).putInt(width).array().copyInto(header, 16)
        ByteBuffer.allocate(4).putInt(height).array().copyInto(header, 20)
        return header
    }

    private class ChunkedResponseBody(
        private val data: ByteArray,
        private val chunkSize: Int,
        private val contentLength: Long = -1L,
        private val readCalls: AtomicInteger? = null,
        private val totalRead: AtomicLong? = null
    ) : ResponseBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = contentLength

        override fun source(): BufferedSource {
            return object : Source {
                private var offset = 0

                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (offset >= data.size) return -1L
                    val toRead = minOf(chunkSize.toLong(), byteCount, (data.size - offset).toLong()).toInt()
                    sink.write(data, offset, toRead)
                    offset += toRead
                    readCalls?.incrementAndGet()
                    totalRead?.addAndGet(toRead.toLong())
                    return toRead.toLong()
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        }
    }
}
