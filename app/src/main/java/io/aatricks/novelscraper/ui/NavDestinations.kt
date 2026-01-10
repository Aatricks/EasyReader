package io.aatricks.novelscraper.ui

import kotlinx.serialization.Serializable

@Serializable
object ReaderRoute

@Serializable
object ExploreRoute

@Serializable
data class NovelDetailsRoute(
    val url: String,
    val source: String
)
