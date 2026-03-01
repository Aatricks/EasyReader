package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.util.imageAspectRatio
import io.aatricks.novelscraper.ui.util.splitImageLayer
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReaderImageView(
    imageUrl: String,
    altText: String?,
    readerViewModel: ReaderViewModel,
    pageUrl: String,
    contentScale: ContentScale = ContentScale.FillWidth,
    backgroundColor: Color = Color.Black,
    width: Int = 0,
    height: Int = 0,
    side: ContentElement.Image.Side = ContentElement.Image.Side.FULL,
    enableZoom: Boolean = false,
    dynamicHeight: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    val aspectRatioModifier = Modifier.imageAspectRatio(side, width, height)

    // When dynamicHeight is true (scrolling mode zoom - though now disabled), we don't apply aspect ratio to the outer container.
    // When enableZoom is true and dynamicHeight is false (Paged Manga mode), we fillMaxSize so zoom can cover black bars.
    val containerModifier = when {
        dynamicHeight -> Modifier.fillMaxWidth().wrapContentHeight()
        enableZoom -> Modifier.fillMaxSize()
        else -> Modifier.fillMaxWidth().then(aspectRatioModifier).wrapContentHeight()
    }

    // For the image itself, if we are in fillMaxSize mode, we don't want the aspect ratio modifier to clip it
    val imageModifier = if (enableZoom && !dynamicHeight) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().then(aspectRatioModifier)
    }.splitImageLayer(side, width, height)

    if (imageUrl.startsWith("http") || imageUrl.startsWith("file")) {
        val context = LocalContext.current

        val cachedFile = remember(imageUrl) {
            if (imageUrl.startsWith("http")) {
                readerViewModel.contentRepository.getCachedMediaFile(imageUrl)
            } else {
                java.io.File(imageUrl.removePrefix("file://"))
            }
        }

        val imageRequest = remember(imageUrl, pageUrl) {
            val isCached = imageUrl.startsWith("file") || cachedFile.exists()
            val referer = if (imageUrl.startsWith("http")) readerViewModel.contentRepository.getReferer(pageUrl) else null
            
            ImageRequest.Builder(context)
                .data(if (isCached && imageUrl.startsWith("http")) cachedFile else imageUrl)
                .apply {
                    if (referer != null) {
                        httpHeaders(NetworkHeaders.Builder()
                            .set("Referer", referer)
                            .set("User-Agent", "Mozilla/5.0")
                            .build())
                    }
                }
                .crossfade(!isCached)
                .build()
        }
        var isError by remember(imageRequest) { mutableStateOf(false) }
        var isLoading by remember(imageRequest) { mutableStateOf(true) }

        Box(
            modifier = containerModifier
                .background(if (dynamicHeight) Color.Transparent else backgroundColor.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            ZoomableBox(
                modifier = imageModifier,
                enableZoom = enableZoom,
                dynamicHeight = dynamicHeight,
                onTap = onTap
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = altText,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (enableZoom && !dynamicHeight) ContentScale.Fit else contentScale,
                    onSuccess = { isLoading = false },
                    onError = {
                        isError = true
                        isLoading = false
                    }
                )
            }

            if (isLoading && !cachedFile.exists()) {
                CircularProgressIndicator(
                    color = Color.Gray.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }

            if (isError) {
                Text(
                    text = altText ?: "Image unavailable",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    } else {
        var imageData by remember(imageUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var isLoading by remember(imageUrl) { mutableStateOf(true) }
        var hasError by remember(imageUrl) { mutableStateOf(false) }

        LaunchedEffect(imageUrl) {
            try {
                isLoading = true
                hasError = false
                val bytes = readerViewModel.contentRepository.getEpubImage(imageUrl)
                if (bytes != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        val opt = android.graphics.BitmapFactory.Options()
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                    }
                    imageData = bitmap
                } else {
                    hasError = true
                }
            } catch (e: Exception) {
                hasError = true
            } finally {
                isLoading = false
            }
        }

        Box(
            modifier = containerModifier
                .background(if (dynamicHeight) Color.Transparent else backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            ZoomableBox(
                modifier = imageModifier,
                enableZoom = enableZoom,
                dynamicHeight = dynamicHeight,
                onTap = onTap
            ) {
                if (imageData != null) {
                    imageData?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = altText,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = if (enableZoom && !dynamicHeight) ContentScale.Fit else contentScale
                        )
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp).padding(16.dp))
            }
            if (hasError) {
                Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
