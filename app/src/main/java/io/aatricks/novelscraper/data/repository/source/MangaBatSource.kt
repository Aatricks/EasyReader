package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import java.net.URLEncoder

class MangaBatSource : BaseJsoupSource() {
    override val name = "MangaBat"
    override val baseUrl = "https://www.mangabats.com"

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        val url = if (tags.isNotEmpty()) {
            val tagSlug = tags.first().lowercase().replace(" ", "-")
            "$baseUrl/genre/$tagSlug?page=$page"
        } else {
            "$baseUrl/manga-list/hot-manga?page=$page"
        }
        val document = getDocument(url)
        val items = parseListElements(document)
        
        if (items.isEmpty()) {
            return@io parseFallbackHomepageLinks(document).distinctBy { it.url }
        }
        
        items.distinctBy { it.url }
    }

    private fun parseListElements(document: org.jsoup.nodes.Document): List<ExploreItem> {
        val elements = document.select(".list-story-item, .item-story, .story_item, .itemupdate, .list-comic-item-wrap")
        return elements.mapNotNull { element ->
            val titleElement = element.select("h3 a, .item-title, .story_name a").first() 
                ?: element.select("a").firstOrNull { it.text().isNotBlank() }
            
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            if (href.isBlank()) return@mapNotNull null
            
            val img = element.select("img").first()
            val coverUrl = img?.findImage()?.let { resolveUrl(it) }

            ExploreItem(
                title = title.trim(),
                url = resolveUrl(href),
                coverUrl = coverUrl?.ifBlank { null },
                source = name
            )
        }
    }

    private fun parseFallbackHomepageLinks(document: org.jsoup.nodes.Document): List<ExploreItem> {
        return document.select("a[href*='/manga/']").mapNotNull { link ->
            val title = link.text().trim()
            val href = link.attr("href")
            if (title.length <= 5 || title.contains("Chapter", ignoreCase = true)) return@mapNotNull null

            val img = link.parent()?.select("img")?.first() ?: link.closest("div")?.select("img")?.first()
            val coverUrl = img?.findImage()?.let { resolveUrl(it) }

            ExploreItem(
                title = title,
                url = resolveUrl(href),
                coverUrl = coverUrl?.ifBlank { null },
                source = name
            )
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encodedQuery = URLEncoder.encode(query.replace(" ", "_"), "UTF-8")
        val url = "$baseUrl/search/story/$encodedQuery?page=$page"
        val document = getDocument(url)
        parseListElements(document).distinctBy { it.url }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = connect(url).referrer(url).get()
        val title = document.select(".story-info-right h1, h1").text()
        
        val author = extractAuthor(document)
        val summary = document.select("#contentBox, .panel-story-info-description, .story-info-description")
            .first()?.text()?.replace("Description :", "")
            ?.replace(Regex(".*summary: ", RegexOption.IGNORE_CASE), "")?.trim()
        
        val coverUrl = extractCoverUrl(document)
        
        val chapters = document.select(".chapter-name, .chapter-list a, .row a[href*='/chapter-']")
        val chapterList = chapters.map { element ->
            io.aatricks.novelscraper.data.model.ChapterInfo(
                title = element.text(),
                url = resolveUrl(element.attr("href"))
            )
        }.reversed()

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl.ifBlank { null },
            author = author,
            summary = summary,
            chapterCount = chapterList.size,
            source = name,
            readingUrl = chapterList.firstOrNull()?.url,
            chapters = chapterList
        )
    }

    private fun extractAuthor(document: org.jsoup.nodes.Document): String {
        val authorByLink = document.select(".table-value a[href*='search/author'], .info-author a").text()
        if (authorByLink.isNotBlank()) return authorByLink
        
        val authorByLabel = document.select("li:contains(Author) :not(p)").text()
        if (authorByLabel.isNotBlank()) return authorByLabel
        
        return document.select("li:contains(Author)").text()
            .replace("Author(s) :", "")
            .replace("Author(s):", "").trim()
    }

    private fun extractCoverUrl(document: org.jsoup.nodes.Document): String {
        val ogImage = document.select("meta[property='og:image']").attr("content")
        if (ogImage.isNotBlank()) return resolveUrl(ogImage)
        
        val coverImg = document.select(".info-image img, .story-info-left img, .manga-info-pic img").first()
        return resolveUrl(coverImg?.findImage() ?: "")
    }


    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adult", "Adventure", "Comedy", "Cooking", "Doujinshi", "Drama", "Ecchi", "Fantasy", 
        "Gender bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Manhua", "Manhwa", 
        "Martial arts", "Mature", "Mecha", "Medical", "Mystery", "One shot", "Psychological", "Romance", 
        "School life", "Sci fi", "Seinen", "Shoujo", "Shoujo ai", "Shounen", "Shounen ai", "Slice of life", 
        "Smut", "Sports", "Supernatural", "Tragedy", "Webtoons", "Yaoi", "Yuri"
    )
}