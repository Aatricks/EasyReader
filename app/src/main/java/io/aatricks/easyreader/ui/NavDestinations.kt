package io.aatricks.easyreader.ui

import kotlinx.serialization.Serializable

@Serializable
object ReaderRoute

@Serializable
object ExploreRoute

@Serializable
object LibraryRoute

@Serializable
data class NovelDetailsRoute(
    val url: String,
    val source: String
)
