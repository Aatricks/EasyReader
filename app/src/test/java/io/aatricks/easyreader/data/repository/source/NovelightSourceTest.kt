package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

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
}
