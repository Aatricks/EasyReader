package io.aatricks.novelscraper.data.repository.source

import io.aatricks.novelscraper.data.model.ExploreItem
import org.junit.Test
import kotlin.system.measureTimeMillis
import org.junit.Assert.*

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
}
