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

import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun ReaderImageView(
    imageUrl: String,
    altText: String?,
    readerViewModel: ReaderViewModel,
    pageUrl: String,
    contentScale: ContentScale = ContentScale.Fit,
    backgroundColor: Color = Color.Black,
    width: Int = 0,
    height: Int = 0,
    side: ContentElement.Image.Side = ContentElement.Image.Side.FULL,
    enableZoom: Boolean = false,
    dynamicHeight: Boolean = false,
    zoomStateKey: Any? = null,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    lockTapWhileZoomed: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val aspectRatioModifier = Modifier.imageAspectRatio(side, width, height)

    // Hoist loading state so containerModifier can react to it, shrinking the container
    // once the image has loaded to avoid black gaps in long-strip (manhwa) mode.
    var isLoadingHoisted by remember(imageUrl, pageUrl) { mutableStateOf(true) }

    // When dynamicHeight is true (scrolling mode zoom - though now disabled), we don't apply aspect ratio to the outer container.
    // When enableZoom is true and dynamicHeight is false (Paged Manga mode), we fillMaxSize so zoom can cover black bars.
    val containerModifier = when {
        dynamicHeight -> Modifier.fillMaxWidth().wrapContentHeight()
        enableZoom -> Modifier.fillMaxSize()
        else -> {
            // For standard scrolling mode, we limit height to screen height to avoid "zoomed in" feel for very tall high-res images
            val base = Modifier.fillMaxWidth()
                .then(aspectRatioModifier)
                .sizeIn(maxHeight = screenHeight)
            
            if (width <= 0 || height <= 0) {
                if (isLoadingHoisted) {
                    base.defaultMinSize(minHeight = 48.dp)
                } else {
                    base.wrapContentHeight()
                }
            } else {
                base.wrapContentHeight()
            }
        }
    }

    // In scroll mode (no enableZoom), use a transparent background so short panels don't show
    // coloured bars. In paged manga mode, the dark background fills the letterbox area intentionally.
    val effectiveBackground = if (enableZoom) backgroundColor.copy(alpha = 0.5f) else Color.Transparent

    // Use FillHeight + alignment for split images to avoid stretching.
    // The container (ZoomableBox) has the half-image aspect ratio, and FillHeight + alignment
    // handles the cropping perfectly without needing graphicsLayer scaling.
    val isSplit = side != ContentElement.Image.Side.FULL && width > 0 && height > 0

    val imageModifier = when {
        enableZoom && !dynamicHeight && !isSplit -> Modifier.fillMaxSize()
        else -> Modifier.fillMaxWidth().then(aspectRatioModifier)
    }

    val imageAlignment = when {
        !isSplit -> Alignment.Center
        side == ContentElement.Image.Side.LEFT -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }

    val pagedContentScale = when {
        isSplit -> ContentScale.FillHeight // image fills composable height, alignment crops to correct half
        else -> contentScale // Use the passed contentScale (defaulting to Fit)
    }

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

        Box(
            modifier = containerModifier
                .background(if (dynamicHeight) Color.Transparent else effectiveBackground),
            contentAlignment = Alignment.Center
        ) {
            ZoomableBox(
                modifier = imageModifier,
                enableZoom = enableZoom,
                dynamicHeight = dynamicHeight,
                zoomStateKey = zoomStateKey,
                onZoomChanged = onZoomChanged,
                lockTapWhileZoomed = lockTapWhileZoomed,
                onTap = onTap
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = altText,
                    modifier = Modifier.fillMaxSize(),
                    alignment = imageAlignment,
                    contentScale = pagedContentScale,
                    onSuccess = { isLoadingHoisted = false },
                    onError = {
                        isError = true
                        isLoadingHoisted = false
                    }
                )
            }

            if (isLoadingHoisted && !cachedFile.exists()) {
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
        var hasError by remember(imageUrl) { mutableStateOf(false) }

        LaunchedEffect(imageUrl) {
            try {
                isLoadingHoisted = true
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
                isLoadingHoisted = false
            }
        }

        Box(
            modifier = containerModifier
                .background(if (dynamicHeight) Color.Transparent else effectiveBackground),
            contentAlignment = Alignment.Center
        ) {
            ZoomableBox(
                modifier = imageModifier,
                enableZoom = enableZoom,
                dynamicHeight = dynamicHeight,
                zoomStateKey = zoomStateKey,
                onZoomChanged = onZoomChanged,
                lockTapWhileZoomed = lockTapWhileZoomed,
                onTap = onTap
            ) {
                if (imageData != null) {
                    imageData?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = altText,
                            modifier = Modifier.fillMaxSize(),
                            alignment = imageAlignment,
                            contentScale = pagedContentScale
                        )
                    }
                }
            }

            if (isLoadingHoisted) {
                CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp).padding(16.dp))
            }
            if (hasError) {
                Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
