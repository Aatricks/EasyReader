package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ExploreItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.reflect.Method
import kotlin.system.measureTimeMillis

class NovelFireSourceBenchmarkTest {

    @Test
    fun benchmarkSearchNovelsFallback() = runBlocking<Unit> {
        val mockPreferencesManager = mock<PreferencesManager>()
        val okHttpClient = OkHttpClient()
        val source = NovelFireSource(mockPreferencesManager, okHttpClient)

        // Generate a large HTML document to simulate the O(N^2) issue
        val numItems = 2000
        val sb = StringBuilder()
        sb.append("<html><body>")
        for (i in 1..numItems) {
            sb.append("""
                <div class="novel-item">
                    <a href="/book/novel-$i">Novel $i</a>
                    <img src="/cover-$i.jpg">
                    <div class="novel-stats">100 Chapters</div>
                </div>
            """.trimIndent())
        }
        // Also add duplicates to see filtering
        for (i in 1..500) {
            sb.append("""
                <div class="novel-item">
                    <a href="/book/novel-$i">Novel $i Duplicate</a>
                    <img src="/cover-$i.jpg">
                    <div class="novel-stats">100 Chapters</div>
                </div>
            """.trimIndent())
        }
        sb.append("</body></html>")
        val html = sb.toString()

        val document = org.jsoup.Jsoup.parse(html)
        val baseUrl = "https://novelfire.net"

        val time = measureTimeMillis {
            val items = mutableListOf<ExploreItem>()
            val seenUrls = mutableSetOf<String>()
            val bookLinks = document.select("a[href^='/book/']")

            bookLinks.forEach { link ->
                val rawTitle = link.text()
                var title = rawTitle
                title = title.replace(Regex("^\\[\\d+\\]\\s*"), "")
                title = title.replace(Regex("^R\\s*\\d+(\\.\\d+)?\\s*"), "")
                title = title.replace(Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
                title = title.trim()

                val href = link.attr("href")

                if (title.isNotBlank() && !title.equals("Read Now", ignoreCase = true) && !title.contains("Chapter", ignoreCase = true)) {
                    val absoluteUrl = if (href.startsWith("/")) "$baseUrl$href" else href

                    if (seenUrls.add(absoluteUrl)) {
                        val parent = link.closest(".novel-item, .item, .book-item") ?: link.parent()?.parent()
                        val img = parent?.select("img")?.first()
                        var coverUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
                        if (coverUrl.startsWith("/")) coverUrl = "${baseUrl}${coverUrl}"

                        val chapterText = parent?.select(".novel-stats, .stats, .chapters")?.text() ?: ""
                        val chapterCount = Regex("(\\d+)\\s*Chapters", RegexOption.IGNORE_CASE).find(chapterText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        items.add(ExploreItem(
                            title = title,
                            url = absoluteUrl,
                            coverUrl = coverUrl.ifBlank { null },
                            source = "NovelFire",
                            chapterCount = chapterCount
                        ))
                    }
                }
            }
            println("Found ${items.size} unique items")
        }

        println("Optimized Benchmark completed in ${time}ms")
    }
}
