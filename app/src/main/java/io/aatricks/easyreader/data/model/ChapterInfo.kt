package io.aatricks.easyreader.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChapterInfo(
    val title: String,
    val url: String,
    val number: Double? = null
)
