package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class SmartSourceTest {

    @Mock
    lateinit var preferencesManager: PreferencesManager

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Mock
    lateinit var call: Call

    private lateinit var source: SmartSource

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        source = SmartSource(preferencesManager, okHttpClient)
    }

    private fun mockResponse(url: String, html: String) {
        val response = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html".toMediaType()))
            .build()
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
    }

    @Test
    fun `non-url search returns empty`() {
        val result = runBlocking { source.searchNovels("solo leveling", page = 1) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `url search returns single scraped item`() {
        val pageUrl = "https://example.test/manga/some-series"
        val html = """
            <html><head>
              <meta property="og:title" content="Some Series | ExampleSite"/>
              <meta property="og:image" content="https://example.test/img/cover.webp"/>
              <meta property="og:description" content="An intriguing tale."/>
            </head><body>
              <h1>Some Series</h1>
              <a href="/manga/some-series/chapter-2">Chapter 2</a>
              <a href="/manga/some-series/chapter-1">Chapter 1</a>
            </body></html>
        """.trimIndent()

        mockResponse(pageUrl, html)

        val result = runBlocking { source.searchNovels(pageUrl, page = 1) }
        assertEquals(1, result.size)
        val item = result[0]
        assertEquals("Some Series", item.title)
        assertEquals("https://example.test/img/cover.webp", item.coverUrl)
        assertEquals("An intriguing tale.", item.summary)
        assertEquals(2, item.chapterCount)
        assertEquals("https://example.test/manga/some-series/chapter-1", item.chapters[0].url)
        assertEquals("https://example.test/manga/some-series/chapter-2", item.chapters[1].url)
        assertEquals("Smart Scrape", item.source)
    }

    @Test
    fun `parses Madara theme detail page`() {
        val pageUrl = "https://madara.test/manga/madara-series/"
        val html = """
            <html><body>
              <div class="post-title">
                <h1>Madara Series</h1>
              </div>
              <div class="summary_image">
                <img src="https://madara.test/uploads/cover.jpg"/>
              </div>
              <div class="author-content">
                <a href="/manga-author/yamada/">Yamada</a>
              </div>
              <div class="description-summary">
                <div class="summary__content">A Madara CMS test fixture.</div>
              </div>
              <ul class="version-chap">
                <li class="wp-manga-chapter"><a href="/manga/madara-series/chapter-3/">Chapter 3</a></li>
                <li class="wp-manga-chapter"><a href="/manga/madara-series/chapter-2/">Chapter 2</a></li>
                <li class="wp-manga-chapter"><a href="/manga/madara-series/chapter-1/">Chapter 1</a></li>
              </ul>
            </body></html>
        """.trimIndent()

        mockResponse(pageUrl, html)

        val item = runBlocking { source.getNovelDetails(pageUrl) }
        assertEquals("Madara Series", item.title)
        assertEquals("Yamada", item.author)
        assertEquals("A Madara CMS test fixture.", item.summary)
        assertEquals("https://madara.test/uploads/cover.jpg", item.coverUrl)
        assertEquals(3, item.chapterCount)
        assertEquals("https://madara.test/manga/madara-series/chapter-1/", item.chapters[0].url)
        assertEquals("https://madara.test/manga/madara-series/chapter-3/", item.chapters[2].url)
        assertEquals(item.chapters.first().url, item.readingUrl)
    }

    @Test
    fun `generic og fallback when no Madara markup`() {
        val pageUrl = "https://nocms.test/series/example"
        val html = """
            <html><head>
              <title>Example Series</title>
              <meta property="og:image" content="/static/cover.png"/>
              <meta name="description" content="Generic fallback summary."/>
            </head><body>
              <article>
                <h1>Example Series</h1>
                <a href="/series/example/ch-1">Ch 1</a>
                <a href="/series/example/ch-10">Ch 10</a>
                <a href="/series/example/ch-2">Ch 2</a>
                <a href="/about">About us</a>
              </article>
            </body></html>
        """.trimIndent()

        mockResponse(pageUrl, html)

        val item = runBlocking { source.getNovelDetails(pageUrl) }
        assertEquals("Example Series", item.title)
        assertEquals("https://nocms.test/static/cover.png", item.coverUrl)
        assertEquals("Generic fallback summary.", item.summary)
        assertEquals(3, item.chapterCount)
        // Numeric sort: 1, 2, 10 — not lexical 1, 10, 2.
        assertEquals("https://nocms.test/series/example/ch-1", item.chapters[0].url)
        assertEquals("https://nocms.test/series/example/ch-2", item.chapters[1].url)
        assertEquals("https://nocms.test/series/example/ch-10", item.chapters[2].url)
        assertNull(item.author)
    }

    @Test
    fun `getPopularNovels returns empty`() {
        val result = runBlocking { source.getPopularNovels(page = 1) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `failed fetch yields empty search result without throwing`() {
        val pageUrl = "https://example.test/manga/broken"
        val response = Response.Builder()
            .request(Request.Builder().url(pageUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Server Error")
            .body("".toResponseBody(null))
            .build()
        whenever(okHttpClient.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)

        val result = runBlocking { source.searchNovels(pageUrl, page = 1) }
        assertTrue(result.isEmpty())
        assertNotNull(source)
    }
}
