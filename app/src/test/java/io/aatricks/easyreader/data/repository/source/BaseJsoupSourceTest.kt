package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class BaseJsoupSourceTest {

    @Mock
    lateinit var preferencesManager: PreferencesManager

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Mock
    lateinit var call: Call

    private lateinit var source: TestSource

    class TestSource(
        preferencesManager: PreferencesManager,
        okHttpClient: OkHttpClient
    ) : BaseJsoupSource(preferencesManager, okHttpClient) {
        override val name = "Test"
        override val baseUrl = "https://example.com"
        override suspend fun getPopularNovels(page: Int, tags: List<String>) = emptyList<ExploreItem>()
        override suspend fun searchNovels(query: String, page: Int) = emptyList<ExploreItem>()
        override suspend fun getNovelDetails(url: String) = ExploreItem(title = "", url = "", source = "Test")

        fun testGetDocument(url: String): Document = getDocument(url)
    }

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        source = TestSource(preferencesManager, okHttpClient)
    }

    @Test
    fun `getDocument uses injected okHttpClient`() {
        val url = "https://example.com/test"
        val html = "<html><body><h1>Test</h1></body></html>"
        val response = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html".toMediaType()))
            .build()

        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)

        val doc = source.testGetDocument(url)

        assertNotNull(doc)
        assertEquals("Test", doc.select("h1").text())
        
        verify(okHttpClient).newCall(any())
    }

    @Test(expected = java.io.IOException::class)
    fun `getDocument throws exception on failure`() {
        val url = "https://example.com/fail"
        val response = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody(null))
            .build()

        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)

        source.testGetDocument(url)
    }
}
