package io.aatricks.easyreader.data.repository.source

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ExploreItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.mock
import kotlin.system.measureTimeMillis

class NovelFireSourceBenchmarkTest {

    @Test
    fun benchmarkItemsNone() {
        val numItems = 5000
        val dummyUrls = (1..numItems).map { "https://novelfire.net/book/novel-$it" }

        val itemsList = mutableListOf<ExploreItem>()
        val timeBaseline = measureTimeMillis {
            for (url in dummyUrls) {
                if (itemsList.none { it.url == url }) {
                    itemsList.add(
                        ExploreItem(
                            title = "Title",
                            url = url,
                            source = "NovelFire"
                        )
                    )
                }
            }
        }

        val itemsListOptimized = mutableListOf<ExploreItem>()
        val addedUrls = HashSet<String>()
        val timeOptimized = measureTimeMillis {
            for (url in dummyUrls) {
                if (addedUrls.add(url)) {
                    itemsListOptimized.add(
                        ExploreItem(
                            title = "Title",
                            url = url,
                            source = "NovelFire"
                        )
                    )
                }
            }
        }

        println("Baseline time (O(N^2) list.none): ${timeBaseline}ms")
        println("Optimized time (HashSet): ${timeOptimized}ms")

        assertEquals(numItems, itemsList.size)
        assertEquals(numItems, itemsListOptimized.size)
    }

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

    private fun cleanNovelTitleOriginal(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(Regex("^\\[\\d+\\]\\s*"), "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(Regex("^R\\s*\\d+(\\.\\d+)?\\s*"), "")
        // Remove Rank 123 at start
        clean = clean.replace(Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }

    companion object {
        private val BRACKET_NUMBER_REGEX = Regex("^\\[\\d+\\]\\s*")
        private val R_NUMBER_REGEX = Regex("^R\\s*\\d+(\\.\\d+)?\\s*")
        private val RANK_PREFIX_REGEX = Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE)
    }

    private fun cleanNovelTitleOptimized(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(BRACKET_NUMBER_REGEX, "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(R_NUMBER_REGEX, "")
        // Remove Rank 123 at start
        clean = clean.replace(RANK_PREFIX_REGEX, "")
        return clean.trim()
    }

    @Test
    fun benchmarkCleanNovelTitle() {
        val titles = listOf(
            "[123] Some Novel Name",
            "R 14.8 Another Novel Name",
            "Rank 42 A Third Novel Name",
            "Just a regular name",
            "Rank 123 [123] Double trouble name",
            "Nothing to replace here 123"
        )

        // Warmup
        for (i in 0 until 1000) {
            for (title in titles) {
                cleanNovelTitleOriginal(title)
                cleanNovelTitleOptimized(title)
            }
        }

        val iterations = 100_000
        val timeOriginal = measureTimeMillis {
            for (i in 0 until iterations) {
                for (title in titles) {
                    cleanNovelTitleOriginal(title)
                }
            }
        }

        val timeOptimized = measureTimeMillis {
            for (i in 0 until iterations) {
                for (title in titles) {
                    cleanNovelTitleOptimized(title)
                }
            }
        }

        println("Baseline - Time to run $iterations iterations: $timeOriginal ms")
        println("Optimized - Time to run $iterations iterations: $timeOptimized ms")
        println("Improvement: ${timeOriginal - timeOptimized} ms")
    }
}
