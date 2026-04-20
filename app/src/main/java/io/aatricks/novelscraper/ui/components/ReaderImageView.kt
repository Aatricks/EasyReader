package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.util.ImageDimensions
import io.aatricks.novelscraper.ui.util.effectiveImageDimensions
import io.aatricks.novelscraper.ui.util.imageAspectRatio
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun shouldUseLightweightImageContainer(enableZoom: Boolean): Boolean = !enableZoom

internal fun shouldUseAnimatedImageLoadingUi(enableZoom: Boolean, isCached: Boolean): Boolean =
    !shouldUseLightweightImageContainer(enableZoom) && !isCached

internal fun shouldSubsampleReaderImage(enableZoom: Boolean, dynamicHeight: Boolean): Boolean =
    !enableZoom && !dynamicHeight

internal fun calculateReaderInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return 1
    }
    var inSampleSize = 1
    var halfWidth = sourceWidth / 2
    var halfHeight = sourceHeight / 2

    while (halfWidth / inSampleSize >= targetWidth && halfHeight / inSampleSize >= targetHeight) {
        inSampleSize *= 2
    }

    return inSampleSize.coerceAtLeast(1)
}

internal fun decodeReaderBitmap(
    bytes: ByteArray,
    targetWidth: Int,
    targetHeight: Int,
    subsample: Boolean
): android.graphics.Bitmap? {
    if (!subsample) {
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    val boundsOptions = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = calculateReaderInSampleSize(
            sourceWidth = boundsOptions.outWidth,
            sourceHeight = boundsOptions.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }

    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
}

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
    resolvedWidth: Int = 0,
    resolvedHeight: Int = 0,
    side: ContentElement.Image.Side = ContentElement.Image.Side.FULL,
    enableZoom: Boolean = false,
    dynamicHeight: Boolean = false,
    zoomStateKey: Any? = null,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    onDimensionsResolved: ((String, Int, Int) -> Unit)? = null,
    lockTapWhileZoomed: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val shouldSubsampleImage = shouldSubsampleReaderImage(
        enableZoom = enableZoom,
        dynamicHeight = dynamicHeight
    )
    var runtimeDimensions by remember(imageUrl, pageUrl) { mutableStateOf<ImageDimensions?>(null) }
    val effectiveDimensions = effectiveImageDimensions(
        declaredWidth = width,
        declaredHeight = height,
        resolvedWidth = resolvedWidth.takeIf { it > 0 } ?: runtimeDimensions?.width ?: 0,
        resolvedHeight = resolvedHeight.takeIf { it > 0 } ?: runtimeDimensions?.height ?: 0
    )
    val effectiveWidth = effectiveDimensions?.width ?: 0
    val effectiveHeight = effectiveDimensions?.height ?: 0
    val aspectRatioModifier = Modifier.imageAspectRatio(side, effectiveWidth, effectiveHeight)
    val hasResolvedAspectRatio = effectiveDimensions != null

    // Hoist loading state so containerModifier can react to it, shrinking the container
    // once the image has loaded to avoid black gaps in long-strip (manhwa) mode.
    var isLoadingHoisted by remember(imageUrl, pageUrl) { mutableStateOf(true) }

    // When dynamicHeight is true (scrolling mode zoom - though now disabled), we don't apply aspect ratio to the outer container.
    // When enableZoom is true and dynamicHeight is false (Paged Manga mode), we fillMaxSize so zoom can cover black bars.
    val containerModifier = when {
        dynamicHeight -> Modifier.fillMaxWidth().wrapContentHeight()
        enableZoom -> Modifier.fillMaxSize()
        else -> {
            if (hasResolvedAspectRatio) {
                Modifier.fillMaxWidth()
                    .then(aspectRatioModifier)
                    .sizeIn(maxHeight = screenHeight)
                    .wrapContentHeight()
            } else {
                Modifier.fillMaxWidth()
                    .wrapContentHeight()
                    .let { base ->
                        if (isLoadingHoisted) base.defaultMinSize(minHeight = 48.dp) else base
                    }
            }
        }
    }

    // In scroll mode (no enableZoom), use a transparent background so short panels don't show
    // coloured bars. In paged manga mode, the dark background fills the letterbox area intentionally.
    val effectiveBackground = if (enableZoom) backgroundColor.copy(alpha = 0.5f) else Color.Transparent

    // Use FillHeight + alignment for split images to avoid stretching.
    // The container (ZoomableBox) has the half-image aspect ratio, and FillHeight + alignment
    // handles the cropping perfectly without needing graphicsLayer scaling.
    val isSplit = side != ContentElement.Image.Side.FULL && effectiveWidth > 0 && effectiveHeight > 0

    val imageModifier = when {
        enableZoom && !dynamicHeight && !isSplit -> Modifier.fillMaxSize()
        hasResolvedAspectRatio -> Modifier.fillMaxWidth().then(aspectRatioModifier)
        else -> Modifier.fillMaxWidth().wrapContentHeight()
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
        val isCachedImage = imageUrl.startsWith("file") || cachedFile.exists()
        val showAnimatedLoadingUi = shouldUseAnimatedImageLoadingUi(
            enableZoom = enableZoom,
            isCached = isCachedImage
        )

        val imageRequest = remember(imageUrl, pageUrl, isCachedImage, showAnimatedLoadingUi) {
            val referer = if (imageUrl.startsWith("http")) readerViewModel.contentRepository.getReferer(pageUrl) else null
            
            ImageRequest.Builder(context)
                .data(if (isCachedImage && imageUrl.startsWith("http")) cachedFile else imageUrl)
                .apply {
                    if (shouldSubsampleImage) {
                        size(screenWidthPx, screenHeightPx)
                        scale(Scale.FIT)
                        precision(Precision.INEXACT)
                    }
                    if (referer != null) {
                        httpHeaders(NetworkHeaders.Builder()
                            .set("Referer", referer)
                            .set("User-Agent", "Mozilla/5.0")
                            .build())
                    }
                }
                .crossfade(showAnimatedLoadingUi)
                .build()
        }
        var isError by remember(imageRequest) { mutableStateOf(false) }

        Box(
            modifier = containerModifier
                .background(if (dynamicHeight) Color.Transparent else effectiveBackground),
            contentAlignment = Alignment.Center
        ) {
            val imageContent: @Composable () -> Unit = {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = altText,
                    modifier = if (hasResolvedAspectRatio || enableZoom) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth().wrapContentHeight()
                    },
                    alignment = imageAlignment,
                    contentScale = pagedContentScale,
                    onSuccess = { state: AsyncImagePainter.State.Success ->
                        val resolved = ImageDimensions(
                            width = state.result.image.width,
                            height = state.result.image.height
                        )
                        if (resolved.width > 0 && resolved.height > 0) {
                            runtimeDimensions = resolved
                            onDimensionsResolved?.invoke(imageUrl, resolved.width, resolved.height)
                        }
                        isLoadingHoisted = false
                    },
                    onError = {
                        isError = true
                        isLoadingHoisted = false
                    }
                )
            }

            if (shouldUseLightweightImageContainer(enableZoom)) {
                Box(
                    modifier = imageModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTap?.invoke() }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    imageContent()
                }
            } else {
                ZoomableBox(
                    modifier = imageModifier,
                    enableZoom = enableZoom,
                    dynamicHeight = dynamicHeight,
                    zoomStateKey = zoomStateKey,
                    onZoomChanged = onZoomChanged,
                    lockTapWhileZoomed = lockTapWhileZoomed,
                    onTap = onTap
                ) {
                    imageContent()
                }
            }

            if (isLoadingHoisted && showAnimatedLoadingUi) {
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
        val showAnimatedLoadingUi = !shouldUseLightweightImageContainer(enableZoom)

        LaunchedEffect(imageUrl) {
            try {
                isLoadingHoisted = true
                hasError = false
                val bytes = readerViewModel.contentRepository.getEpubImage(imageUrl)
                if (bytes != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeReaderBitmap(
                            bytes = bytes,
                            targetWidth = screenWidthPx,
                            targetHeight = screenHeightPx,
                            subsample = shouldSubsampleImage
                        )
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
            val imageContent: @Composable () -> Unit = {
                if (imageData != null) {
                    imageData?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = altText,
                            modifier = if (hasResolvedAspectRatio || enableZoom) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.fillMaxWidth().wrapContentHeight()
                            },
                            alignment = imageAlignment,
                            contentScale = pagedContentScale
                        )
                    }
                }
            }

            if (shouldUseLightweightImageContainer(enableZoom)) {
                Box(
                    modifier = imageModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTap?.invoke() }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    imageContent()
                }
            } else {
                ZoomableBox(
                    modifier = imageModifier,
                    enableZoom = enableZoom,
                    dynamicHeight = dynamicHeight,
                    zoomStateKey = zoomStateKey,
                    onZoomChanged = onZoomChanged,
                    lockTapWhileZoomed = lockTapWhileZoomed,
                    onTap = onTap
                ) {
                    imageContent()
                }
            }

            if (isLoadingHoisted && showAnimatedLoadingUi) {
                CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp).padding(16.dp))
            }
            if (hasError) {
                Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
