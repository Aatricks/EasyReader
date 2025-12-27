package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class StandardEbooksSource : NovelSource {
    override val name = "Standard Ebooks"
    override val baseUrl = "https://standardebooks.org"

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        // Feed doesn't support pagination easily, fallback to HTML for pages > 1
        if (page > 1) {
            return@withContext scrapeHtmlList("$baseUrl/ebooks?page=$page")
        }

        // Using "New Releases" feed as popular/explore since it's an atom feed which is cleaner
        val url = "$baseUrl/feeds/atom/new-releases"
        try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .parser(org.jsoup.parser.Parser.xmlParser())
                .get()

            val entries = document.select("entry")
            entries.map { entry ->
                val title = entry.select("title").first()?.text() ?: "Unknown"
                val author = entry.select("author name").first()?.text()
                val summary = entry.select("summary").text()
                // Find EPUB link
                val epubLink = entry.select("link[type=application/epub+zip]").attr("href")
                // Find cover link (thumbnail)
                val coverUrl = entry.select("media|thumbnail").attr("url") // Namespace handling might need care, Jsoup usually handles it as tag name "media:thumbnail" or just "thumbnail" if namespace stripped.
                    .ifEmpty { entry.select("thumbnail").attr("url") }

                // The "url" for our purpose should probably be the book details page or the download link.
                // The atom feed entry id is the url to the book page.
                val bookUrl = entry.select("id").text()

                ExploreItem(
                    title = title,
                    url = bookUrl,
                    coverUrl = coverUrl,
                    author = author,
                    summary = summary,
                    source = name
                )
            }
        } catch (e: Exception) {
            // Fallback to HTML scraping if feed fails
            scrapeHtmlList("$baseUrl/ebooks")
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/ebooks?query=$encodedQuery&page=$page"
        scrapeHtmlList(url)
    }

    private fun scrapeHtmlList(url: String): List<ExploreItem> {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        // Based on text dump:
        // 1. [13][louis-joseph-vance...]-[14]...
        // [15]The Lone Wolf
        // [16]Louis Joseph Vance

        // This looks like an ordered list <ol> or <ul>.
        val listItems = document.select("ol.ebooks-list li, ul.ebooks-list li, li")
        // Again guessing class names. Standard Ebooks uses semantic HTML usually.

        // Let's look for specific structure: image link followed by title link followed by author link.

        val entries = document.select("li:has(img)") // Select list items that have images

        entries.forEach { entry ->
            val titleLink = entry.select("a[href^='/ebooks/']").find { it.text().isNotBlank() }
            if (titleLink != null) {
                val title = titleLink.text()
                val href = titleLink.attr("href") // relative path e.g. /ebooks/author/title
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"

                val author = entry.select("p.author, span.author").text() // Guessing selector
                    .ifEmpty { entry.text().substringAfter(title).trim() } // Fallback

                val img = entry.select("img").first()
                val coverUrl = img?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$baseUrl$it"
                }

                items.add(ExploreItem(
                    title = title,
                    url = fullUrl,
                    coverUrl = coverUrl,
                    author = author,
                    source = name
                ))
            }
        }
        return items
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        val title = document.select("h1").first()?.text() ?: "Unknown"
        val author = document.select("h2, .author").first()?.text()

        // Summary might be in a section or div
        val summary = document.select("section#summary, .description, div[itemprop=description]").text()

        val coverUrl = document.select("img[itemprop=image], .cover img").attr("src").let {
             if (it.startsWith("http")) it else "$baseUrl$it"
        }

        // Standard ebooks are usually single "book" entities, chapter count is not always prominent or relevant (it's one epub).
        // But we can check word count or reading time if available.

        // Resolve readingUrl (EPUB link)
        // Standard Ebooks page usually has "Compatible epub" link
        val epubHref = document.select("a[href$=.epub]").firstOrNull()?.attr("href")
        val readingUrl = if (epubHref != null) {
            if (epubHref.startsWith("http")) epubHref else "$baseUrl$epubHref"
        } else {
            null
        }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl,
            author = author,
            summary = summary,
            source = name,
            readingUrl = readingUrl
        )
    }
}
