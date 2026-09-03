package io.aatricks.easyreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.aatricks.easyreader.data.model.LibraryItem

@Composable
fun rememberLibraryCoverImageRequest(item: LibraryItem): ImageRequest {
    val context = LocalContext.current

    return remember(item.coverImageUrl, item.url, item.sourceName) {
        val refererUrl = item.baseNovelUrl.ifBlank { item.url }
        val uri = try {
            java.net.URI(refererUrl)
        } catch (_: Exception) {
            null
        }

        var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else refererUrl
        if (item.sourceName == "MangaBat" || referer.contains("mangabat")) {
            referer = "https://www.mangabats.com/"
        } else if (referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(item.coverImageUrl)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
            .crossfade(true)
            .build()
    }
}
