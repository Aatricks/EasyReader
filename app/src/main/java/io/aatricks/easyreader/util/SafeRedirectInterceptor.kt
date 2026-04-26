package io.aatricks.easyreader.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException

class SafeRedirectInterceptor : Interceptor {

    companion object {
        private const val MAX_REDIRECTS = 20
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        // Validate initial request URL
        if (!UrlSecurity.isSafeUrlSynchronous(request.url)) {
            throw IOException("Unsafe URL blocked: ${request.url}")
        }

        var response = chain.proceed(request)
        var followUpCount = 0

        while (response.isRedirect) {
            val location = response.header("Location") ?: break

            // Resolve the new URL against the current request URL
            val newUrl = response.request.url.resolve(location) ?: break

            // Validate the new URL
            if (!UrlSecurity.isSafeUrlSynchronous(newUrl)) {
                response.close()
                throw IOException("Unsafe redirect blocked: $newUrl")
            }

            if (++followUpCount > MAX_REDIRECTS) {
                response.close()
                throw ProtocolException("Too many redirects: $followUpCount")
            }

            val statusCode = response.code
            val method = request.method

            // Determine new method and body handling based on status code
            val newMethod = when (statusCode) {
                307, 308 -> method // Preserve method
                else -> "GET" // 301, 302, 303 -> Change to GET
            }

            // Build new request
            val requestBuilder = request.newBuilder().url(newUrl)

            if (newMethod == "GET" && method != "GET") {
                requestBuilder.method("GET", null)
                requestBuilder.removeHeader("Content-Type")
                requestBuilder.removeHeader("Content-Length")
                requestBuilder.removeHeader("Transfer-Encoding")
            } else {
                 // For 307/308, we should preserve the body.
                 // However, Application Interceptors cannot easily replay request bodies
                 // if they are one-shot streams.
                 // For this fix, we assume replayable bodies or GET requests.
                 // If the body is one-shot, chain.proceed() would fail anyway on retry without a new body.
                 // Since we are reusing the 'request' object's body (if any), it depends on the body implementation.
                 // For standard scraping (GET), this is fine.
            }

            // Close the previous response body
            response.close()

            // execute the new request
            request = requestBuilder.build()
            response = chain.proceed(request)
        }

        return response
    }
}
