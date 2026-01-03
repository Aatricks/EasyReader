package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL

class SmartScraperSource(override val baseUrl: String) : NovelSource {
    override val name = URL(baseUrl).host

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getPopularNovels(page: Int): List<ExploreItem> = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(baseUrl)
            .userAgent(userAgent)
            .referrer(baseUrl)
            .timeout(10000)
            .get()

        val items = mutableListOf<ExploreItem>()
        // Heuristic: look for links that look like book/novel links
        val links = document.select("a[href*='novel'], a[href*='book'], a[href*='manga'], a[href*='story']")
        
        links.forEach { link ->
            val title = link.text().trim()
            val href = link.absUrl("href")
            
            if (title.length > 3 && href.isNotBlank() && href != baseUrl && !href.contains("category") && !href.contains("genre")) {
                if (items.none { it.url == href }) {
                    // Try to find image nearby
                    val img = link.parent()?.select("img")?.first() ?: link.closest("div")?.select("img")?.first()
                    items.add(ExploreItem(
                        title = title,
                        url = href,
                        coverUrl = img?.absUrl("src"),
                        source = name
                    ))
                }
            }
        }
        items
    }

    override suspend fun searchNovels(query: String, page: Int): List<ExploreItem> {
        // Hard to implement generic search without knowing the URL structure
        return emptyList()
    }

    override suspend fun getNovelDetails(url: String): ExploreItem = withContext(Dispatchers.IO) {
        val document = Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(url)
            .timeout(10000)
            .get()

        val title = document.select("h1, .title, .name").first()?.text() ?: document.title()
        val author = document.select("[class*='author'], [href*='author']").first()?.text()
        val summary = document.select("[class*='summary'], [class*='description'], #summary, #description").first()?.text()
        val coverUrl = document.select("img[class*='cover'], img[src*='cover'], .fixed-img img").first()?.absUrl("src")
        
        // Find first chapter link
        val chapterLink = document.select("a[href*='chapter-1'], a[href*='ch-1'], a:contains(Read Now), a:contains(First Chapter)").first()
            ?: document.select("a[href*='chapter']").first()
            
        val readingUrl = chapterLink?.absUrl("href")

        // Find all chapter links
        val chapters = document.select("a[href*='chapter'], a[href*='ch-'], a[href*='ep-']").map { element ->
            io.aatricks.novelscraper.data.model.ChapterInfo(
                title = element.text(),
                url = element.absUrl("href")
            )
        }.distinctBy { it.url }

        ExploreItem(
            title = title,
            url = url,
            coverUrl = coverUrl,
            author = author,
            summary = summary,
            source = name,
            readingUrl = readingUrl,
            chapters = chapters
        )
    }
}
