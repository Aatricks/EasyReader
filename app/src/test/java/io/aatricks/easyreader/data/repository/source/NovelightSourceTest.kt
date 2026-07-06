package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ChapterInfo
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

class NovelightSourceTest {

    private val source = NovelightSource(mock<PreferencesManager>(), mock<OkHttpClient>())

    @Test
    fun `parses search-live light-novel results and ignores other groups`() {
        val html = """
            <div class="searches">
              <div id="ln-search-results" class="search-results">
                <div class="search-results__inner">
                  <a href="/book/shadow-slave-novel" class="manga-list-item">
                    <div class="image image-cover"><img src="/media/book/poster/ss.webp" alt=""></div>
                    <div class="manga-list__info"><div class="title">Shadow Slave (Novel)</div></div>
                  </a>
                </div>
              </div>
              <div id="user-search-results" class="search-results">
                <a href="/character/sunny" class="manga-list-item"><div class="title">Sunny</div></a>
              </div>
            </div>
        """.trimIndent()

        val items = source.parseSearchHtml(html)

        assertEquals(1, items.size)
        assertEquals("Shadow Slave (Novel)", items[0].title)
        assertEquals("https://novelight.net/book/shadow-slave-novel", items[0].url)
        assertEquals("https://novelight.net/media/book/poster/ss.webp", items[0].coverUrl)
    }

    @Test
    fun `parses chapter pagination html into numbered chapters`() {
        val html = """
            <a href="/book/chapter/318322" class="chapter ">
              <div class="title">3050 chapter - <span>Rivers of Blood</span></div>
              <div class="chapter-info"><span class="author">Sejong</span><span class="date">19.06.2026</span></div>
            </a>
            <a href="/book/chapter/318320" class="chapter ">
              <div class="title">3049 chapter - <span>Use of Weapons</span></div>
            </a>
        """.trimIndent()

        val chapters = source.parseChapterPaginationHtml(html)

        assertEquals(2, chapters.size)
        assertEquals("Chapter 3050 - Rivers of Blood", chapters[0].title)
        assertEquals(3050.0, chapters[0].number!!, 0.0)
        assertEquals("https://novelight.net/book/chapter/318322", chapters[0].url)
        assertEquals("Chapter 3049 - Use of Weapons", chapters[1].title)
    }

    @Test
    fun `keeps decimal chapter numbers`() {
        val html = """<a href="/book/chapter/191867" class="chapter"><div class="title">193.1 chapter - <span>The Black Seed</span></div></a>"""
        val chapters = source.parseChapterPaginationHtml(html)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 193.1 - The Black Seed", chapters[0].title)
        assertEquals(193.1, chapters[0].number!!, 0.0001)
    }

    @Test
    fun `parses homepage listing cards within a section`() {
        val html = """
            <html><body>
              <div class="block popular">
                <a href="/book/foo" class="manga-item">
                  <div class="poster image image-cover"><img class="lazy-image" src="/media/book/poster/foo.jpg" alt="Foo Title"></div>
                  <span>Web Novel • 4.2</span>
                  <div class="title clamp clamp-2">Foo Title</div>
                </a>
              </div>
              <div class="block recently">
                <a href="/book/bar" class="manga-item"><div class="title">Bar</div></a>
              </div>
            </body></html>
        """.trimIndent()
        val document = Jsoup.parse(html, "https://novelight.net/")

        val popular = source.parseListingCards(document, ".block.popular")

        assertEquals(1, popular.size)
        assertEquals("Foo Title", popular[0].title)
        assertEquals("https://novelight.net/book/foo", popular[0].url)
        assertEquals("https://novelight.net/media/book/poster/foo.jpg", popular[0].coverUrl)
    }

    @Test
    fun `extracts BOOK_ID from embedded script`() {
        val document = Jsoup.parse("""<html><head><script>let BOOK_ID = "95"; let X = 1;</script></head><body></body></html>""")
        assertEquals("95", source.extractBookId(document))
        assertNull(source.extractBookId(Jsoup.parse("<html><body>no id here</body></html>")))
    }

    @Test
    fun `exposes novelight identity`() {
        assertEquals("Novelight", source.name)
        assertEquals("https://novelight.net", source.baseUrl)
        assertTrue(source.baseUrl.startsWith("https://"))
    }

    // --- Chapter paging orchestration (assembleChapters) -------------------------------------
    //
    // Regression cover for the "jumps from ch. 102 to 403" bug: novelight's pagination clamps
    // out-of-range pages to the last page's content instead of returning empty, and failed pages
    // used to be silently dropped, persisting a chapter list with holes.

    private fun loadedPage(numbers: IntRange): NovelightSource.PageOutcome.Loaded =
        NovelightSource.PageOutcome.Loaded(
            numbers.map { ChapterInfo(title = "Chapter $it", url = "/book/chapter/$it", number = it.toDouble()) }
        )

    // Newest page is 1 (131..180) down to a short last page 4 (1..30); pages beyond 4 clamp to page
    // 4's content, exactly like novelight's real endpoint.
    private val bookPages = mapOf(1 to 131..180, 2 to 81..130, 3 to 31..80, 4 to 1..30)
    private fun bookPageOrClamp(page: Int) = loadedPage(bookPages[page] ?: bookPages.getValue(4))

    @Test
    fun `assembleChapters collects every page with no gap and without flooding`() = runBlocking {
        var calls = 0
        val chapters = source.assembleChapters { page ->
            calls++
            bookPageOrClamp(page)
        }
        assertEquals((1..180).toList(), chapters.mapNotNull { it.number?.toInt() })
        assertTrue("should stop at the real last page, not flood; was $calls calls", calls < 10)
    }

    @Test
    fun `assembleChapters stops at the clamped out-of-range page instead of looping to the cap`() = runBlocking {
        // All four pages are full (50 each), so the only stop signal is the clamp repeating page 4.
        val fullBook = mapOf(1 to 151..200, 2 to 101..150, 3 to 51..100, 4 to 1..50)
        var calls = 0
        val chapters = source.assembleChapters { page ->
            calls++
            loadedPage(fullBook[page] ?: fullBook.getValue(4))
        }
        assertEquals(200, chapters.size)
        assertEquals((1..200).toList(), chapters.mapNotNull { it.number?.toInt() })
        assertTrue("should detect the clamp and stop; was $calls calls", calls < 12)
    }

    @Test
    fun `assembleChapters aborts rather than persist a hole when a known page never recovers`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                source.assembleChapters { page ->
                    if (page == 3) NovelightSource.PageOutcome.Failed else bookPageOrClamp(page)
                }
            }
        }
    }

    @Test
    fun `assembleChapters degrades past a gated page without failing the load`() = runBlocking {
        val chapters = source.assembleChapters { page ->
            if (page == 2) NovelightSource.PageOutcome.Gated else bookPageOrClamp(page)
        }
        val numbers = chapters.mapNotNull { it.number?.toInt() }
        assertEquals(130, chapters.size) // page 2 (81..130) skipped, the rest still load
        assertTrue("gated page's chapters are dropped, not backfilled", numbers.none { it in 81..130 })
    }

    // --- Page fetch retry / status classification (fetchChapterPage) -------------------------

    private fun httpResponse(code: Int): Response = Response.Builder()
        .request(Request.Builder().url("https://novelight.net/book/ajax/chapter-pagination?book_id=1&page=1").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("msg")
        .body("".toResponseBody("application/json".toMediaType()))
        .build()

    private fun sourceWith(client: OkHttpClient) = NovelightSource(mock<PreferencesManager>(), client)

    @Test
    fun `fetchChapterPage retries a transient 429 then succeeds`() {
        val call = mock<Call>()
        val client = mock<OkHttpClient> { whenever(it.newCall(any())).thenReturn(call) }
        whenever(call.execute()).thenReturn(httpResponse(429), httpResponse(200))

        val outcome = runBlocking { sourceWith(client).fetchChapterPage("1", 5) }

        assertTrue(outcome is NovelightSource.PageOutcome.Loaded)
        verify(call, times(2)).execute()
    }

    @Test
    fun `fetchChapterPage gives up after exhausting retries on persistent 429`() {
        val call = mock<Call>()
        val client = mock<OkHttpClient> { whenever(it.newCall(any())).thenReturn(call) }
        whenever(call.execute()).thenAnswer { httpResponse(429) }

        val outcome = runBlocking { sourceWith(client).fetchChapterPage("1", 5) }

        assertTrue(outcome is NovelightSource.PageOutcome.Failed)
        verify(call, times(4)).execute() // 1 initial attempt + 3 retries
    }

    @Test
    fun `fetchChapterPage treats a 403 as gated without retrying`() {
        val call = mock<Call>()
        val client = mock<OkHttpClient> { whenever(it.newCall(any())).thenReturn(call) }
        whenever(call.execute()).thenReturn(httpResponse(403))

        val outcome = runBlocking { sourceWith(client).fetchChapterPage("1", 5) }

        assertTrue(outcome is NovelightSource.PageOutcome.Gated)
        verify(call, times(1)).execute()
    }
}
