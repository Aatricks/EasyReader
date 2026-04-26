package io.aatricks.easyreader.data.model

sealed class ContentResult {
    data class Success(
        val elements: List<ContentElement>,
        val title: String? = null,
        val url: String,
        val textCount: Int? = null,
        val imageCount: Int? = null
    ) : ContentResult()
    data class Error(val message: String, val exception: Exception? = null) : ContentResult()
}
