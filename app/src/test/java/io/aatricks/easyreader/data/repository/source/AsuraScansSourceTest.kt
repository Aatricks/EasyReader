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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class AsuraScansSourceTest {

    @Mock
    lateinit var preferencesManager: PreferencesManager

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Mock
    lateinit var call: Call

    private lateinit var source: AsuraScansSource

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        source = AsuraScansSource(preferencesManager, okHttpClient)
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
    fun `parses series cards from browse grid`() {
        val html = """
            <html><body>
              <div id="series-grid">
                <div class="series-card" data-series-id="42">
                  <a href="/comics/breakers-030ff47a">
                    <img src="https://cdn.asurascans.com/asura-images/covers/breakers.7f8d96-400.webp" alt="Breakers"/>
                  </a>
                  <div class="p-3">
                    <a href="/comics/breakers-030ff47a"><h3 class="text-sm">Breakers</h3></a>
                    <span class="text-xs font-medium text-white bg-white/10 px-2 py-1 rounded">235<!-- --> Chapters</span>
                  </div>
                </div>
                <div class="series-card" data-series-id="43">
                  <a href="/comics/villain-to-kill-030ff47a">
                    <img src="https://cdn.asurascans.com/asura-images/covers/villain.webp" alt="Villain To Kill"/>
                  </a>
                  <div class="p-3">
                    <a href="/comics/villain-to-kill-030ff47a"><h3>Villain To Kill</h3></a>
                    <span class="text-xs font-medium text-white bg-white/10 px-2 py-1 rounded">82 Chapters</span>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        mockResponse("https://asurascans.com/browse", html)

        val items = runBlocking { source.getPopularNovels(page = 1) }

        assertEquals(2, items.size)
        assertEquals("Breakers", items[0].title)
        assertEquals("https://asurascans.com/comics/breakers-030ff47a", items[0].url)
        assertEquals(
            "https://cdn.asurascans.com/asura-images/covers/breakers.7f8d96-400.webp",
            items[0].coverUrl
        )
        assertEquals("Asura Scans", items[0].source)
        assertEquals(235, items[0].chapterCount)
        assertEquals("Villain To Kill", items[1].title)
        assertEquals(82, items[1].chapterCount)
    }

    @Test
    fun `parses series detail page and chapter list`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Breakers | Asura Scans"/>
              <meta property="og:image" content="https://cdn.asurascans.com/asura-images/covers/breakers.webp"/>
              <meta property="og:description" content="A story about a dragged-in protagonist."/>
            </head><body>
              <h1 class="text-xl">Breakers</h1>
              <a href="/browse?author=Chwiryong" class="text-sm">Chwiryong</a>
              <a href="/browse?artist=Redice%20Studio" class="text-sm">Redice Studio</a>
              <div class="chapter-list">
                <a href="/comics/breakers-030ff47a/chapter/1" class="py-3 rounded-md">
                  <span>First Chapter</span>
                </a>
                <a href="/comics/breakers-030ff47a/chapter/82" class="py-3 rounded-md">
                  <span>Latest Chapter</span>
                </a>
                <a href="/comics/breakers-030ff47a/chapter/82" class="group">
                  <span class="font-medium">Chapter 82</span>
                  <span class="truncate text-white/50">Intersection #4</span>
                  <span class="text-sm text-white/40">Oct 06, 2025</span>
                </a>
                <a href="/comics/breakers-030ff47a/chapter/2" class="group">
                  <span class="font-medium">Chapter 2</span>
                  <span class="truncate text-white/50">Entrance #2</span>
                  <span class="text-sm text-white/40">Sep 29, 2025</span>
                </a>
                <a href="/comics/breakers-030ff47a/chapter/1" class="group">
                  <span class="font-medium">Chapter 1</span>
                  <span class="truncate text-white/50">Entrance #1</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()

        mockResponse("https://asurascans.com/comics/breakers-030ff47a", html)

        val item = runBlocking { source.getNovelDetails("https://asurascans.com/comics/breakers-030ff47a") }

        assertEquals("Breakers", item.title)
        assertEquals("Asura Scans", item.source)
        assertEquals("Chwiryong", item.author)
        assertEquals("A story about a dragged-in protagonist.", item.summary)
        assertEquals("https://cdn.asurascans.com/asura-images/covers/breakers.webp", item.coverUrl)
        // 3 unique chapter URLs (1, 2, 82) — "First/Latest Chapter" buttons share URLs with real rows.
        assertEquals(3, item.chapterCount)
        // Sorted ascending by chapter number.
        assertEquals("https://asurascans.com/comics/breakers-030ff47a/chapter/1", item.chapters[0].url)
        assertEquals("https://asurascans.com/comics/breakers-030ff47a/chapter/2", item.chapters[1].url)
        assertEquals("https://asurascans.com/comics/breakers-030ff47a/chapter/82", item.chapters[2].url)
        assertEquals("Chapter 1 — Entrance #1", item.chapters[0].title)
        assertEquals("Chapter 2 — Entrance #2", item.chapters[1].title)
        assertEquals(item.chapters.first().url, item.readingUrl)
        assertNotNull(item.summary)
    }

    @Test
    fun `real browse html yields covers for every card`() {
        val html = javaClass.classLoader!!.getResourceAsStream("asura_browse_real.html")!!
            .bufferedReader().readText()
        mockResponse("https://asurascans.com/browse", html)

        val items = runBlocking { source.getPopularNovels(page = 1) }
        assertTrue("expected at least 10 items, got ${items.size}", items.size >= 10)
        items.forEach { item ->
            assertTrue("missing cover for ${item.title}: ${item.coverUrl}",
                !item.coverUrl.isNullOrBlank() && item.coverUrl!!.startsWith("https://"))
        }
    }

    @Test
    fun `real series detail yields cover and many chapters`() {
        val html = javaClass.classLoader!!.getResourceAsStream("asura_series_real.html")!!
            .bufferedReader().readText()
        val seriesUrl = "https://asurascans.com/comics/breakers-030ff47a"
        mockResponse(seriesUrl, html)

        val item = runBlocking { source.getNovelDetails(seriesUrl) }
        assertTrue("cover missing: ${item.coverUrl}",
            !item.coverUrl.isNullOrBlank() && item.coverUrl!!.startsWith("https://"))
        assertTrue("expected many chapters, got ${item.chapterCount}", item.chapterCount >= 50)
    }

    @Test
    fun `popular browse mode yields covers for every card`() {
        val html = javaClass.classLoader!!.getResourceAsStream("asura_browse_popular_real.html")!!
            .bufferedReader().readText()
        mockResponse("https://asurascans.com/browse?order=popular", html)

        val items = runBlocking { source.getNovels(BrowseMode.POPULAR, page = 1) }
        assertTrue("expected at least 10 items, got ${items.size}", items.size >= 10)
        items.forEach { item ->
            assertTrue("missing cover for ${item.title}: ${item.coverUrl}",
                !item.coverUrl.isNullOrBlank() && item.coverUrl!!.startsWith("https://"))
        }
    }

    @Test
    fun `falls back to og title when h1 missing`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Some Series | Asura Scans"/>
            </head><body></body></html>
        """.trimIndent()

        mockResponse("https://asurascans.com/comics/some-series-030ff47a", html)

        val item = runBlocking { source.getNovelDetails("https://asurascans.com/comics/some-series-030ff47a") }

        assertEquals("Some Series", item.title)
        assertTrue(item.chapters.isEmpty())
    }
}
