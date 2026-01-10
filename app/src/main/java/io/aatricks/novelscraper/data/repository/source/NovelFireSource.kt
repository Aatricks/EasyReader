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

class NovelFireSource : BaseJsoupSource() {
    override val name = "NovelFire"
    override val baseUrl = "https://novelfire.net"

    private fun cleanNovelTitle(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(Regex("^\\[\\d+\\]\\s*"), "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(Regex("^R\\s*\\d+(\\.\\d+)?\\s*"), "")
        // Remove Rank 123 at start
        clean = clean.replace(Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }
    
    override suspend fun getPopularNovels(page: Int, tags: List<String>): List<ExploreItem> = io {
        val url = if (tags.isNotEmpty()) {
            val tag = tags.first() // Use first tag for now
            val tagSlug = tag.lowercase().replace(" ", "-")
            "$baseUrl/genre-$tagSlug/sort-popular/status-all/all-novel?page=$page"
        } else {
            "$baseUrl/genre-all/sort-popular/status-all/all-novel?page=$page"
        }
        val document = getDocument(url)

        val items = mutableListOf<ExploreItem>()
        val bookLinks = document.select("a[href^='/book/']")

        bookLinks.forEach { link ->
            val rawTitle = link.text()
            val title = cleanNovelTitle(rawTitle)
            val href = link.attr("href")
            // Avoid empty titles or "Read Now" links if they exist
            if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                 // Try to find image nearby
                 val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                 val img = parent?.select("img")?.first()
                 val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""

                 // Deduplicate by URL
                 val absoluteUrl = resolveUrl(href)
                 if (items.none { it.url == absoluteUrl }) {
                     items.add(ExploreItem(
                         title = title,
                         url = absoluteUrl,
                         coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                         source = name
                     ))
                 }
            }
        }
        items
    }
    
    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> = io {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/ajax/searchLive?inputContent=$encodedQuery"
        
        try {
            val response = connect(url)
                .ignoreContentType(true)
                .execute()
                .body()
            
            val json = org.json.JSONObject(response)
            val data = json.getJSONArray("data")
            val items = mutableListOf<ExploreItem>()
            
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val rawTitle = obj.getString("title")
                val title = cleanNovelTitle(rawTitle)
                val slug = obj.getString("slug")
                val image = obj.getString("image")
                
                items.add(ExploreItem(
                    title = title,
                    url = "$baseUrl/book/$slug",
                    coverUrl = resolveUrl(image),
                    source = name,
                    rank = obj.optInt("rank").toString(),
                    chapterCount = obj.optInt("total_chapter")
                ))
            }
            items
        } catch (e: Exception) {
            val fallbackUrl = "$baseUrl/genre-all/sort-popular/status-all/all-novel?keyword=$encodedQuery&page=$page"
            val document = getDocument(fallbackUrl)

            val items = mutableListOf<ExploreItem>()
            val bookLinks = document.select("a[href^='/book/']")

            bookLinks.forEach { link ->
                val rawTitle = link.text()
                val title = cleanNovelTitle(rawTitle)
                val href = link.attr("href")

                 if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                     val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                     val img = parent?.select("img")?.first()
                     val coverUrl = img?.findImage()?.let { resolveUrl(it) } ?: ""
                     val absoluteUrl = resolveUrl(href)

                     if (items.none { it.url == absoluteUrl }) {
                         items.add(ExploreItem(
                             title = title,
                             url = absoluteUrl,
                             coverUrl = if (coverUrl.isBlank()) null else coverUrl,
                             source = name
                         ))
                     }
                 }
            }
            items
        }
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = io {
        val document = getDocument(url)

        val rawTitle = document.select("h1, .novel-title").first()?.text() ?: "Unknown Title"
        val title = cleanNovelTitle(rawTitle)
        val author = document.select(".author a, .author").first()?.text()

        val summaryElement = document.select(".summary .content p, .summary .content, #summary, .description").first()
        val summary = if (summaryElement != null) {
            document.select(".summary .content p").joinToString("\n\n") { it.text() }
                .ifEmpty { summaryElement.text() }
        } else null

        val coverImg = document.select(".fixed-img .cover img, .book-cover img, .novel-cover img").first()
        val coverUrl = resolveUrl(coverImg?.findImage() ?: "")

        val infoText = document.text()
        val chapterCountRegex = Regex("(\\d+)\\s*Chapters", RegexOption.IGNORE_CASE)
        val chapterCount = chapterCountRegex.find(infoText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val rankRegex = Regex("RANK\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val rank = rankRegex.find(infoText)?.groupValues?.get(1)

        val ratingRegex = Regex("Average score is\\s+([0-9.]+)", RegexOption.IGNORE_CASE)
        val rating = ratingRegex.find(infoText)?.groupValues?.get(1)

        val chaptersPageHref = document.select("a[href$='/chapters']").attr("href")
        val chaptersUrl = if (chaptersPageHref.isNotBlank()) {
            resolveUrl(chaptersPageHref)
        } else {
            if (url.endsWith("/chapters")) url else "$url/chapters"
        }

        val firstPageDoc = try {
            getDocument(chaptersUrl)
        } catch (e: Exception) {
            document
        }

        val allChapters = mutableListOf<ChapterInfo>()

        fun parseChapters(doc: org.jsoup.nodes.Document): List<ChapterInfo> {
            return doc.select(".chapter-list li a, ul.chapters li a, .chapters li a").mapNotNull { element ->
                val chapterUrl = resolveUrl(element.attr("href"))

                var rawTitle = element.attr("title")
                if (rawTitle.isBlank()) rawTitle = element.select(".chapter-title").text()
                if (rawTitle.isBlank()) rawTitle = element.text()

                var cleanTitle = rawTitle
                cleanTitle = cleanTitle.replace(Regex("\\d+\\s+(year|month|day|hour|minute|second)s?\\s+ago.*$"), "").trim()

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

        allChapters.addAll(parseChapters(firstPageDoc))

        val paginationLinks = firstPageDoc.select("ul.pagination .page-item .page-link")
        var maxPage = 1

        paginationLinks.forEach { link ->
            val pageNum = link.text().toIntOrNull()
            if (pageNum != null && pageNum > maxPage) {
                maxPage = pageNum
            } else {
                val href = link.attr("href")
                val hrefPage = href.substringAfter("page=").toIntOrNull()
                if (hrefPage != null && hrefPage > maxPage) {
                    maxPage = hrefPage
                }
            }
        }

        if (maxPage > 1) {
            val deferredPages = kotlinx.coroutines.coroutineScope {
                (2..maxPage).map { page ->
                    async {
                        try {
                            val pageUrl = if (chaptersUrl.contains("?")) "$chaptersUrl&page=$page" else "$chaptersUrl?page=$page"
                            val pageDoc = getDocument(pageUrl)
                            parseChapters(pageDoc)
                        } catch (e: Exception) {
                            emptyList<ChapterInfo>()
                        }
                    }
                }
            }
            allChapters.addAll(deferredPages.awaitAll().flatten())
        }

        val readingUrl = if (allChapters.isNotEmpty()) {
            allChapters.first().url
        } else {
            val readNowHref = document.select("a:contains(Read Now)").attr("href")
            if (readNowHref.isNotBlank()) {
                resolveUrl(readNowHref)
            } else url
        }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = if (coverUrl.isBlank()) null else coverUrl,
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
