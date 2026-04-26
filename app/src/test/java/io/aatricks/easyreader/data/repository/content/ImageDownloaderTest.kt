package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ImageRequestPriority
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ImageDownloaderTest {

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
    fun `executeImageRequest respects host throttling`() = runBlocking {
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
        assertTrue(timestamps[1] - timestamps[0] >= 400) // HOST_REQUEST_SPACING_MS is 450L
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
}
