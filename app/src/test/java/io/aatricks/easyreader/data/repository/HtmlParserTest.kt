package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ContentElement
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlParserTest {

    private val parser = HtmlParser()

    @Test
    fun `extracts chapter page images from Asura reader markup`() {
        val html = """
            <html><body>
              <div class="select-none">
                <div class="max-w-full mx-auto">
                  <div data-page="0" class="w-full">
                    <img src="https://cdn.asurascans.com/asura-images/chapters/x/1/a.webp"
                         alt="Page 1 - Chapter 1 - X" data-page-index="0"/>
                  </div>
                  <div data-page="1" class="w-full">
                    <img src="https://cdn.asurascans.com/asura-images/chapters/x/1/b.webp"
                         alt="Page 2 - Chapter 1 - X" data-page-index="1"/>
                  </div>
                  <div data-page="2" class="w-full">
                    <img src="https://cdn.asurascans.com/asura-images/chapters/x/1/c.webp"
                         alt="Page 3 - Chapter 1 - X" data-page-index="2"/>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://asurascans.com/comics/x/chapter/1")
        val elements = parser.parse(document, "https://asurascans.com/comics/x/chapter/1")
        val images = elements.filterIsInstance<ContentElement.Image>()

        assertEquals(3, images.size)
        assertEquals("https://cdn.asurascans.com/asura-images/chapters/x/1/a.webp", images[0].url)
        assertEquals("https://cdn.asurascans.com/asura-images/chapters/x/1/c.webp", images[2].url)
    }

    @Test
    fun `extracts MangaBat chapter images from current reader markup`() {
        val pageUrl = "https://www.mangabats.com/manga/mercenary-enrollment/chapter-238"
        val html = """
            <html><body>
              <div class="container-chapter-reader">
                <img src="https://img-r1.2xstorage.com/mercenary-enrollment/238/0.webp"
                     alt="Mercenary Enrollment Chapter 238 page 1 - Mangabat"
                     loading="lazy">
                <img src="https://img-r1.2xstorage.com/mercenary-enrollment/238/1.webp"
                     alt="Mercenary Enrollment Chapter 238 page 2 - Mangabat"
                     loading="lazy">
              </div>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, pageUrl)
        val elements = parser.parse(document, pageUrl)
        val images = elements.filterIsInstance<ContentElement.Image>()

        assertEquals(2, images.size)
        assertEquals("https://img-r1.2xstorage.com/mercenary-enrollment/238/0.webp", images[0].url)
        assertEquals("https://img-r1.2xstorage.com/mercenary-enrollment/238/1.webp", images[1].url)
    }

    @Test
    fun `real Asura chapter html yields many CDN images`() {
        val html = javaClass.classLoader!!.getResourceAsStream("asura_chapter_real.html")!!
            .bufferedReader().readText()
        val pageUrl = "https://asurascans.com/comics/breakers-030ff47a/chapter/82"
        val document = Jsoup.parse(html, pageUrl)
        val elements = parser.parse(document, pageUrl)
        val images = elements.filterIsInstance<ContentElement.Image>()

        assertTrue("expected many chapter images, got ${images.size}", images.size >= 8)
        images.forEach { img ->
            assertTrue("non-CDN image leaked: ${img.url}",
                img.url.startsWith("https://cdn.asurascans.com/asura-images/chapters/"))
        }
    }

    @Test
    fun `parses Novelight div-per-paragraph chapter prose and drops ad blocks`() {
        val pageUrl = "https://novelight.net/book/chapter/191867"
        val html = """
            <html><body>
              <div class="chapter-text">
                <div>Several days later.</div>
                <div>In front of me stood a row of steel dummies.</div>
                <div class="advertisment"><script>var ad=1;</script>SPONSORED</div>
              </div>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, pageUrl)
        val elements = parser.parse(document, pageUrl)
        val text = elements.filterIsInstance<ContentElement.Text>().joinToString(" ") { it.content }

        assertTrue("expected prose text, got '$text'", text.contains("Several days later"))
        assertTrue(text.contains("steel dummies"))
        assertTrue("ad text leaked into prose: '$text'", !text.contains("SPONSORED"))
    }
}
