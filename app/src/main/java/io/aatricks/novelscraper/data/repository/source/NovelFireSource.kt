package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class NovelFireSource : NovelSource {
    override val name = "NovelFire"
    override val baseUrl = "https://novelfire.net"

    override suspend fun getPopularNovels(page: Int, tag: String?): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = if (tag != null) {
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre/$tagSlug/sort-popular/status-all/all-novel?page=$page"
        } else {
            "$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page"
        }
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        // Based on the text view, items are in a list. I need to guess the selectors or use general structure.
        // The text view shows:
        // [23]Shadow Slave-[24][gif]
        // Ongoing
        // [25]Shadow Slave
        // Action Adventure Fantasy Romance

        // I will try to select based on common classes or structure if I can.
        // Since I can't inspect element, I have to guess standard patterns or rely on the text structure found.
        // Usually list items are in something like `.list-item` or `.book-item`.
        // Let's assume standard scraping isn't easy without exact selectors.
        // However, I saw "Rank Overal ranking" in the text dump of ranking page.
        // Let's try to target the structure I saw in search results which seemed cleaner.
        // Search result structure:
        // * [85]Bugged From the Start...
        // Bugged From the Start...
        // 80 Chapters

        // It seems to be a list of items.
        // The text output suggests links are distinct.

        // I'll try a broad selector for now and refine if I can.
        // Many novel sites use .list-novel .row or similar.

        // Let's try to find elements that contain an image and a title link.
        val elements = document.select(".row .col-lg-2, .row .col-md-3, .book-item, .item")
        // This is a shot in the dark without devtools.
        // But wait, the text dump shows:
        // [25]Shadow Slave
        // Action Adventure Fantasy Romance

        // If I look at the text dump of the search page:
        // [85]Bugged From the Start: I Spawned as a Parasite R 15112
        // Bugged From the Start: I Spawned as a Parasite
        // 80 Chapters

        // It looks like `ul.list-novel li` or `div.list-novel div.item`.

        // I will try to be robust.

        // Generic approach: Find links that look like book links.
        val bookLinks = document.select("a[href^='/book/']")

        bookLinks.forEach { link ->
            val title = link.text()
            val href = link.attr("href")
            // Avoid empty titles or "Read Now" links if they exist
            if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                 // Try to find image nearby
                 val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                 val img = parent?.select("img")?.first()
                 var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                 if (coverUrl.startsWith("/")) coverUrl = "$baseUrl$coverUrl"

                 // Deduplicate by URL
                 if (items.none { it.url == "$baseUrl$href" }) {
                     items.add(ExploreItem(
                         title = title,
                         url = "$baseUrl$href",
                         coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                         source = name
                     ))
                 }
            }
        }

        // If the generic approach fails (too many or too few), I might need to filter.
        // The text dump showed "[23]Shadow Slave-[24][gif...]"
        // This implies an image link then a text link.

        items
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // NovelFire uses an AJAX endpoint for live search
        val url = "$baseUrl/ajax/searchLive?inputContent=$encodedQuery"
        
        try {
            val response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .ignoreContentType(true)
                .execute()
                .body()
            
            // The response is JSON: {"status":200,"data":[{"title":"...","slug":"...","image":"...","rank":...}]}
            val json = org.json.JSONObject(response)
            val data = json.getJSONArray("data")
            val items = mutableListOf<ExploreItem>()
            
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val title = obj.getString("title")
                val slug = obj.getString("slug")
                val image = obj.getString("image")
                
                items.add(ExploreItem(
                    title = title,
                    url = "$baseUrl/book/$slug",
                    coverUrl = if (image.startsWith("http")) image else "$baseUrl/$image",
                    source = name,
                    rank = obj.optInt("rank").toString(),
                    chapterCount = obj.optInt("total_chapter")
                ))
            }
            return@withContext items
        } catch (e: Exception) {
            // Fallback to old search if AJAX fails or returns something else
            val fallbackUrl = "$baseUrl/genre-all/sort-popular/status-all/all-novel?keyword=$encodedQuery&page=$page"
            val document = Jsoup.connect(fallbackUrl)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()

            val items = mutableListOf<ExploreItem>()
            val bookLinks = document.select("a[href^='/book/']")

            bookLinks.forEach { link ->
                val title = link.text()
                val href = link.attr("href")

                 if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                     val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                     val img = parent?.select("img")?.first()
                     var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                     if (coverUrl.startsWith("/")) coverUrl = "$baseUrl$coverUrl"

                     if (items.none { it.url == "$baseUrl$href" }) {
                         items.add(ExploreItem(
                             title = title,
                             url = "$baseUrl$href",
                             coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                             source = name
                         ))
                     }
                 }
            }
            return@withContext items
        }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        val title = document.select("h1, .novel-title").first()?.text() ?: "Unknown Title"
        val author = document.select(".author a, .author").first()?.text()
        
        // Refined summary selector based on observed HTML
        val summaryElement = document.select(".summary .content p, .summary .content, #summary, .description").first()
        val summary = if (summaryElement != null) {
            document.select(".summary .content p").joinToString("\n\n") { it.text() }
                .ifEmpty { summaryElement.text() }
        } else null
        
        var coverUrl = document.select(".fixed-img .cover img, .book-cover img, .novel-cover img").attr("src")
            .ifEmpty { document.select(".fixed-img .cover img, .book-cover img, .novel-cover img").attr("data-src") }
        
        if (coverUrl.isNotBlank() && !coverUrl.startsWith("http")) {
            coverUrl = "$baseUrl${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
        }

        // Chapter count from text like "2724Chapters"
        val infoText = document.text()
        val chapterCountRegex = Regex("(\\d+)\\s*Chapters", RegexOption.IGNORE_CASE)
        val chapterCount = chapterCountRegex.find(infoText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val rankRegex = Regex("RANK\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val rank = rankRegex.find(infoText)?.groupValues?.get(1)

        val ratingRegex = Regex("Average score is\\s+([0-9.]+)", RegexOption.IGNORE_CASE)
        val rating = ratingRegex.find(infoText)?.groupValues?.get(1)

        // Find Reading URL (First Chapter)
        // Typically found in "Read Now" button or first item in chapter list
        val readNowHref = document.select("a:contains(Read Now)").attr("href")
        val readingUrl = if (readNowHref.isNotBlank()) {
            if (readNowHref.startsWith("http")) readNowHref else "$baseUrl$readNowHref"
        } else {
            // Fallback to first chapter in list
            val firstChapterHref = document.select(".chapter-list a, ul.chapters a, .chapters a").first()?.attr("href")
            if (firstChapterHref != null) {
                if (firstChapterHref.startsWith("http")) firstChapterHref else "$baseUrl$firstChapterHref"
            } else {
                null
            }
        }

        // Fetch full chapter list
        val chapters = document.select(".chapter-list a, ul.chapters a, .chapters a").map { element ->
            val chapterUrl = element.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }
            io.aatricks.novelscraper.data.model.ChapterInfo(
                title = element.text(),
                url = chapterUrl
            )
        }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl,
            author = author,
            summary = summary,
            chapterCount = chapterCount,
            rank = rank,
            rating = rating,
            source = name,
            readingUrl = readingUrl,
            chapters = chapters
        )
    }

    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
        "Horror", "Josei", "Martial Arts", "Mature", "Mystery", "Psychological", "Romance", "School Life",
        "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xuanhuan"
    )
}
