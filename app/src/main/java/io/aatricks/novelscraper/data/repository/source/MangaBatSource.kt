package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import java.net.URLEncoder

class MangaBatSource : BaseJsoupSource() {
    override val name = "MangaBat"
    override val baseUrl = "https://www.mangabats.com"

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        val url = if (tags.isNotEmpty()) {
            val tag = tags.first() // MangaBat only supports one tag in URL
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre/$tagSlug?page=$page"
        } else {
            "$baseUrl/manga-list/hot-manga?page=$page"
        }
        val document = getDocument(url)

        val items = mutableListOf<ExploreItem>()
        // Add .itemupdate for the main page style list
        val elements = document.select(".list-story-item, .item-story, .story_item, .itemupdate, .list-comic-item-wrap")

        elements.forEach { element ->
            // Try to find the title element - prioritize h3 a or specific title classes
            val titleElement = element.select("h3 a, .item-title, .story_name a").first() 
                ?: element.select("a").firstOrNull { it.text().isNotBlank() }
            
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            
            // Find the image - it might be in a different link than the title
            val img = element.select("img").first()
            val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = resolveUrl(href)
                items.add(ExploreItem(
                    title = title.trim(),
                    url = absoluteUrl,
                    coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                    source = name
                ))
            }
        }
        
        // Generic fallback for homepage (unchanged)
        if (items.isEmpty()) {
            document.select("a[href*='/manga/']").forEach { link ->
                val title = link.text().trim()
                val href = link.attr("href")
                if (title.length > 5 && !title.contains("Chapter", ignoreCase = true) && items.none { it.url.contains(href) }) {
                    val absoluteUrl = resolveUrl(href)
                    val img = link.parent()?.select("img")?.first() ?: link.closest("div")?.select("img")?.first()
                    val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

                    items.add(ExploreItem(
                        title = title,
                        url = absoluteUrl,
                        coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                        source = name
                    ))
                }
            }
        }
        
        items.distinctBy { it.url }
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encodedQuery = URLEncoder.encode(query.replace(" ", "_"), "UTF-8")
        // Correct search URL for Mangabat is /search/story/
        val url = "$baseUrl/search/story/$encodedQuery?page=$page"
        
        val document = getDocument(url)

        val items = mutableListOf<ExploreItem>()
        val elements = document.select(".list-story-item, .item-story, .story_item, .itemupdate")

        elements.forEach { element ->
            // Try to find the title element - prioritize h3 a or specific title classes
            val titleElement = element.select("h3 a, .item-title, .story_name a").first() 
                ?: element.select("a").firstOrNull { it.text().isNotBlank() }
            
            val title = titleElement?.text() ?: ""
            val href = titleElement?.attr("href") ?: ""
            
            // Find the image - it might be in a different link than the title
            val img = element.select("img").first()
            val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                val absoluteUrl = resolveUrl(href)
                items.add(ExploreItem(
                    title = title.trim(),
                    url = absoluteUrl,
                    coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                    source = name
                ))
            }
        }
        items.distinctBy { it.url }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = connect(url).referrer(url).get()

        val title = document.select(".story-info-right h1, h1").text()
        
        // Robust author scraping
        var author = document.select(".table-value a[href*='search/author'], .info-author a").text()
        if (author.isBlank()) {
            author = document.select("li:contains(Author) :not(p)").text()
                .ifBlank { document.select("li:contains(Author)").text().replace("Author(s) :", "").replace("Author(s):", "").trim() }
        }
        
        // Improved summary selector
        val summaryElement = document.select("#contentBox, .panel-story-info-description, .story-info-description").first()
        val summary = summaryElement?.text()?.replace("Description :", "")?.replace(Regex(".*summary: ", RegexOption.IGNORE_CASE), "")?.trim()
        
        // Improved coverUrl selector using OpenGraph or specific image alt
        val coverImg = document.select(".info-image img, .story-info-left img, .manga-info-pic img").first()
        val coverUrl = document.select("meta[property='og:image']").attr("content")
            .ifBlank { coverImg?.findImage() ?: "" }
            .let { resolveUrl(it) }
        
        val chapters = document.select(".chapter-name, .chapter-list a, .row a[href*='/chapter-']")
        val chapterCount = chapters.size
        
        val chapterList = chapters.map { element ->
            val chapterUrl = resolveUrl(element.attr("href"))
            io.aatricks.novelscraper.data.model.ChapterInfo(
                title = element.text(),
                url = chapterUrl
            )
        }.reversed() // Unify to Ascending (1 to N)

        // First chapter is the first one in the ascending list
        val readingUrl = chapterList.firstOrNull()?.url

        ExploreItem(
            title = title,
            url = url,
            coverUrl = if (coverUrl.isBlank()) null else coverUrl,
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            source = name,
            readingUrl = readingUrl,
            chapters = chapterList
        )
    }

    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adult", "Adventure", "Comedy", "Cooking", "Doujinshi", "Drama", "Ecchi", "Fantasy", 
        "Gender bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Manhua", "Manhwa", 
        "Martial arts", "Mature", "Mecha", "Medical", "Mystery", "One shot", "Psychological", "Romance", 
        "School life", "Sci fi", "Seinen", "Shoujo", "Shoujo ai", "Shounen", "Shounen ai", "Slice of life", 
        "Smut", "Sports", "Supernatural", "Tragedy", "Webtoons", "Yaoi", "Yuri"
    )
}