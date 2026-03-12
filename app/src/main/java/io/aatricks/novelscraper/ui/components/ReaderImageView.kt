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

    // Hoist loading state so containerModifier can react to it, shrinking the container
    // once the image has loaded to avoid black gaps in long-strip (manhwa) mode.
    var isLoadingHoisted by remember(imageUrl, pageUrl) { mutableStateOf(true) }

    // When dynamicHeight is true (scrolling mode zoom - though now disabled), we don't apply aspect ratio to the outer container.
    // When enableZoom is true and dynamicHeight is false (Paged Manga mode), we fillMaxSize so zoom can cover black bars.
    val containerModifier = when {
        dynamicHeight -> Modifier.fillMaxWidth().wrapContentHeight()
        enableZoom -> Modifier.fillMaxSize()
        else -> {
            val base = Modifier.fillMaxWidth().then(aspectRatioModifier)
            if (width <= 0 || height <= 0) {
                if (isLoadingHoisted) {
                    // While loading with unknown dimensions, enforce a minimum height to prevent
                    // LazyColumn from displaying all items at once (which falsely triggers
                    // end-of-list detection and spurious chapter navigation).
                    base.defaultMinSize(minHeight = 200.dp)
                } else {
                    // After loading, collapse to actual image height — eliminates black gaps
                    // between short manhwa panels whose dimensions were not prefetched.
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

    // Split images in paged mode need a different approach to avoid 2× horizontal stretch.
    // Using scaleX=2/scaleY=1 with ContentScale.Fit stretches the image because the full image
    // fits by width leaving vertical bars, then scaleX doubles only horizontally.
    // Instead: give the composable the HALF-image aspect ratio, use FillHeight so the full
    // image fills the composable height, and crop to left/right via alignment.
    val isSplitInPagedMode = enableZoom && !dynamicHeight && side != ContentElement.Image.Side.FULL

    val imageModifier = when {
        isSplitInPagedMode ->
            // Half-image AR composable; FillHeight + alignment does the cropping (no graphicsLayer).
            Modifier.fillMaxWidth().then(aspectRatioModifier)
        enableZoom && !dynamicHeight ->
            Modifier.fillMaxSize().splitImageLayer(side, width, height)
        else ->
            Modifier.fillMaxWidth().then(aspectRatioModifier).splitImageLayer(side, width, height)
    }

    val imageAlignment = when {
        !isSplitInPagedMode -> Alignment.Center
        side == ContentElement.Image.Side.LEFT -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }

    val pagedContentScale = when {
        !enableZoom || dynamicHeight -> contentScale
        isSplitInPagedMode -> ContentScale.FillHeight  // image fills composable height, alignment crops to correct half
        else -> ContentScale.Fit
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
