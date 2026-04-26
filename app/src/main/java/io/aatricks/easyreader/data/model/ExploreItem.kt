package io.aatricks.easyreader.data.model

data class ExploreItem(
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val summary: String? = null,
    val chapterCount: Int = 0,
    val rank: String? = null,
    val rating: String? = null,
    val source: String,
    val readingUrl: String? = null,
    val chapters: List<ChapterInfo> = emptyList(),
    val genres: List<String> = emptyList()
)
