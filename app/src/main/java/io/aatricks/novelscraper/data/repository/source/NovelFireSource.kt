package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.model.ChapterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class NovelFireSource : NovelSource {
    override val name = "NovelFire"
    override val baseUrl = "https://novelfire.net"

    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = withContext(Dispatchers.IO) {
        val url = if (tags.isNotEmpty()) {
            val tag = tags.first() // Use first tag for now
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre-$tagSlug/sort-popular/status-all/all-novel?page=$page"
        } else {
            "$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page"
        }
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        val elements = document.select(".row .col-lg-2, .row .col-md-3, .book-item, .item")
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
                     var coverUrl = img?.attr("data-src")?.ifEmpty { img?.attr("src") } ?: ""
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

        // Find separate chapters page (NovelFire puts chapters on a separate page now)
        val chaptersPageHref = document.select("a[href$='/chapters']").attr("href")

        // Determine the absolute URL for the chapters page
        val chaptersUrl = if (chaptersPageHref.isNotBlank()) {
            if (chaptersPageHref.startsWith("http")) chaptersPageHref else "$baseUrl$chaptersPageHref"
        } else {
            // If not found, assume it's the current page (fallback) or try constructing it
            if (url.endsWith("/chapters")) url else "$url/chapters"
        }

        // Fetch first page of chapters
        val firstPageDoc = try {
            Jsoup.connect(chaptersUrl)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()
        } catch (e: Exception) {
            document // Fallback to main document if fetch fails
        }

        val allChapters = mutableListOf<ChapterInfo>()

        // Helper to parse chapters from a document
        fun parseChapters(doc: org.jsoup.nodes.Document): List<ChapterInfo> {
            return doc.select(".chapter-list li a, ul.chapters li a, .chapters li a").mapNotNull { element ->
                val chapterUrl = element.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }

                // Title cleanup: Prefer 'title' attribute, then .chapter-title, then text()
                // Raw text is like: "35 Chapter 35: Break (3)1 year ago"
                // Title attr is: "Chapter 35 - 35: Break (3)"

                var rawTitle = element.attr("title")
                if (rawTitle.isBlank()) rawTitle = element.select(".chapter-title").text()
                if (rawTitle.isBlank()) rawTitle = element.text()

                // Fallback cleanup if we only have the messy text
                // Remove the update time at the end (e.g. "1 year ago", "2 hours ago")
                // And remove the leading number if it's just the index (e.g. "35 Chapter...")

                var cleanTitle = rawTitle

                // Remove date suffix (e.g. "1 year ago")
                cleanTitle = cleanTitle.replace(Regex("\\d+\\s+(year|month|day|hour|minute|second)s?\\s+ago.*$"), "").trim()

                // Remove leading number if it repeats (e.g. "35 Chapter 35...")
                // Regex: Start with digits, then space, then "Chapter"
                val leadingNumRegex = Regex("^(\\d+)\\s+(Chapter\\s+\\1.*)")
                val match = leadingNumRegex.find(cleanTitle)
                if (match != null) {
                    cleanTitle = match.groupValues[2]
                }

                if (chapterUrl.isNotBlank()) {
                    ChapterInfo(title = cleanTitle, url = chapterUrl)
                } else null
            }
        }

        // Add chapters from first page
        allChapters.addAll(parseChapters(firstPageDoc))

        // Check for pagination
        // Pagination structure: <ul class="pagination"> ... <li class="page-item"><a href="...?page=28">28</a></li> ... </ul>
        // Find the last page number
        val paginationLinks = firstPageDoc.select("ul.pagination .page-item .page-link")
        var maxPage = 1

        paginationLinks.forEach { link ->
            val pageNum = link.text().toIntOrNull()
            if (pageNum != null && pageNum > maxPage) {
                maxPage = pageNum
            } else {
                // Check href for page number if text is "Last" or similar
                val href = link.attr("href")
                val hrefPage = href.substringAfter("page=").toIntOrNull()
                if (hrefPage != null && hrefPage > maxPage) {
                    maxPage = hrefPage
                }
            }
        }

        // If more pages exist, fetch them in parallel
        if (maxPage > 1) {
            val deferredPages = (2..maxPage).map { page ->
                async {
                    try {
                        val pageUrl = if (chaptersUrl.contains("?")) "$chaptersUrl&page=$page" else "$chaptersUrl?page=$page"
                        val pageDoc = Jsoup.connect(pageUrl)
                            .userAgent("Mozilla/5.0")
                            .timeout(10000)
                            .get()
                        parseChapters(pageDoc)
                    } catch (e: Exception) {
                        emptyList<ChapterInfo>()
                    }
                }
            }

            // Wait for all pages and flatten
            val remainingChapters = deferredPages.awaitAll().flatten()
            allChapters.addAll(remainingChapters)
        }

        // Find Reading URL (First Chapter) from the full list
        val readingUrl = if (allChapters.isNotEmpty()) {
            allChapters.first().url
        } else {
            // Fallback
            val readNowHref = document.select("a:contains(Read Now)").attr("href")
            if (readNowHref.isNotBlank()) {
                if (readNowHref.startsWith("http")) readNowHref else "$baseUrl$readNowHref"
            } else url
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
            chapters = allChapters
        )
    }

    override suspend fun getTags(): List<String> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
        "Horror", "Josei", "Martial Arts", "Mature", "Mystery", "Psychological", "Romance", "School Life",
        "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xuanhuan"
    )
}
