package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ChapterInfo
import io.aatricks.novelscraper.data.repository.source.NovelFireSource
import io.aatricks.novelscraper.data.repository.source.NovelSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class ExploreRepositoryTest {

    @Test
    fun `multi-tag popular novels are intersected by url`() = runBlocking {
        val source = FakeNovelSource(
            name = "TestSource",
            popularByTag = mapOf(
                "Action" to listOf(
                    exploreItem("A1", "url-a1"),
                    exploreItem("Shared", "url-shared")
                ),
                "Drama" to listOf(
                    exploreItem("Shared", "url-shared"),
                    exploreItem("D1", "url-d1")
                )
            )
        )
        val repository = ExploreRepository(mock<Context>(), setOf(source))

        val results = repository.getPopularNovels(page = 1, sourceName = "TestSource", tags = listOf("Action", "Drama"))

        assertEquals(listOf("Shared"), results.map { it.title })
        assertEquals(listOf(listOf("Action"), listOf("Drama")), source.popularRequests.map { it.second })
    }

    @Test
    fun `source names are filtered and details are delegated to the matching source`() = runBlocking {
        val source = FakeNovelSource(name = "TestSource")
        val repository = ExploreRepository(mock<Context>(), setOf(source))

        assertEquals(listOf("TestSource"), repository.getSourceNames())
        assertEquals(emptyList<String>(), repository.getTags("Missing"))
    }

    @Test
    fun `source can still be instantiated`() {
        val source = NovelFireSource(
            mock<PreferencesManager>(),
            mock<OkHttpClient>()
        )
        assertEquals("NovelFire", source.name)
        assertEquals("https://novelfire.net", source.baseUrl)
    }

    private fun exploreItem(title: String, url: String) = io.aatricks.novelscraper.data.model.ExploreItem(
        title = title,
        url = url,
        source = "TestSource",
        chapters = listOf(ChapterInfo("Chapter 1", "$url/ch1"))
    )

    private class FakeNovelSource(
        override val name: String,
        private val popularByTag: Map<String, List<io.aatricks.novelscraper.data.model.ExploreItem>> = emptyMap()
    ) : NovelSource {
        val popularRequests = mutableListOf<Pair<Int, List<String>>>()

        override val baseUrl: String = "https://example.com"

        override suspend fun getPopularNovels(page: Int, tags: List<String>): List<io.aatricks.novelscraper.data.model.ExploreItem> {
            popularRequests += page to tags
            return when {
                tags.isEmpty() -> popularByTag.values.flatten()
                tags.size == 1 -> popularByTag[tags.first()] ?: emptyList()
                else -> emptyList()
            }
        }

        override suspend fun searchNovels(query: String, page: Int): List<io.aatricks.novelscraper.data.model.ExploreItem> = emptyList()
        override suspend fun getNovelDetails(url: String): io.aatricks.novelscraper.data.model.ExploreItem =
            io.aatricks.novelscraper.data.model.ExploreItem(title = url, url = url, source = name)
    }
}
