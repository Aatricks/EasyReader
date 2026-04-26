package io.aatricks.novelscraper.util

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.ProtocolException

class SafeRedirectInterceptorTest {

    private val interceptor = SafeRedirectInterceptor()
    private val chain: Interceptor.Chain = mock()

    @Test
    fun `intercept passes through normal response`() {
        val request = Request.Builder().url("http://8.8.8.8/foo").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()

        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(any())).thenReturn(response)

        val result = interceptor.intercept(chain)
        assertEquals(200, result.code)
    }

    @Test
    fun `intercept follows safe redirect`() {
        val initialUrl = "http://8.8.8.8/start"
        val redirectUrl = "http://8.8.8.8/target"

        val initialRequest = Request.Builder().url(initialUrl).build()

        // First response: 302 Redirect
        val redirectResponse = Response.Builder()
            .request(initialRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("Location", redirectUrl)
            .body("".toResponseBody(null))
            .build()

        // Second response: 200 OK
        val targetRequest = Request.Builder().url(redirectUrl).build()
        val successResponse = Response.Builder()
            .request(targetRequest) // The response request must match the executed request ideally
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()

        whenever(chain.request()).thenReturn(initialRequest)
        // Mock sequential calls to proceed
        // Note: The interceptor creates a NEW request object for the second call.
        // We use check argument matching or just return sequentially.
        whenever(chain.proceed(any())).thenAnswer { invocation ->
            val req = invocation.arguments[0] as Request
            if (req.url.toString() == initialUrl) {
                redirectResponse
            } else if (req.url.toString() == redirectUrl) {
                successResponse
            } else {
                throw IllegalStateException("Unexpected request: ${req.url}")
            }
        }

        val result = interceptor.intercept(chain)
        assertEquals(200, result.code)
        assertEquals(redirectUrl, result.request.url.toString())
    }

    @Test
    fun `intercept blocks unsafe redirect to localhost`() {
        val initialUrl = "http://8.8.8.8/start"
        val unsafeUrl = "http://127.0.0.1/admin"

        val initialRequest = Request.Builder().url(initialUrl).build()

        val redirectResponse = Response.Builder()
            .request(initialRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .header("Location", unsafeUrl)
            .body("".toResponseBody(null))
            .build()

        whenever(chain.request()).thenReturn(initialRequest)
        whenever(chain.proceed(any())).thenReturn(redirectResponse)

        try {
            interceptor.intercept(chain)
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            // Expected
            assertEquals("Unsafe redirect blocked: $unsafeUrl", e.message)
        }
    }

    @Test
    fun `intercept limits redirects`() {
        val initialUrl = "http://8.8.8.8/loop/0"
        val request = Request.Builder().url(initialUrl).build()

        whenever(chain.request()).thenReturn(request)

        // Always return a redirect to the next number
        whenever(chain.proceed(any())).thenAnswer { invocation ->
            val req = invocation.arguments[0] as Request
            val currentUrl = req.url.toString()
            // Extract number
            val num = currentUrl.substringAfterLast("/").toIntOrNull() ?: 0
            val nextUrl = "http://8.8.8.8/loop/${num + 1}"

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", nextUrl)
                .body("".toResponseBody(null))
                .build()
        }

        try {
            interceptor.intercept(chain)
            fail("Should have thrown ProtocolException")
        } catch (e: ProtocolException) {
            // Expected
             // Message might contain "Too many redirects: 21"
             // Assert logic
        }
    }
}
