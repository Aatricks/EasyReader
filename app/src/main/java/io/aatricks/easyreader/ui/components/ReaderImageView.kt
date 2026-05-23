package io.aatricks.easyreader.ui.components

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
import io.aatricks.easyreader.data.repository.content.ChapterPageUrlExtra
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size as CoilSize
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.util.ImageDimensions
import io.aatricks.easyreader.ui.util.effectiveImageDimensions
import io.aatricks.easyreader.ui.util.imageAspectRatio
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.data.repository.content.ImageDownloader
import java.io.File

internal fun shouldUseLightweightImageContainer(enableZoom: Boolean): Boolean = !enableZoom

internal fun shouldUseAnimatedImageLoadingUi(enableZoom: Boolean, isCached: Boolean): Boolean =
    !shouldUseLightweightImageContainer(enableZoom) && !isCached

internal fun shouldSubsampleReaderImage(enableZoom: Boolean, dynamicHeight: Boolean): Boolean =
    !enableZoom && !dynamicHeight

internal fun readerImageRefererSource(imageUrl: String, pageUrl: String): String =
    pageUrl.takeIf { it.isNotBlank() } ?: imageUrl

internal fun readerImageLocalMediaState(
    imageUrl: String,
    cachedMediaFile: (String) -> File
): String {
    if (!imageUrl.startsWith("http")) return ""

    val file = cachedMediaFile(imageUrl)
    return if (file.exists()) {
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    } else {
        "missing"
    }
}

internal fun readerImageRequestCacheKey(
    imageUrl: String,
    localMediaState: String,
    retryTrigger: Long
): String? =
    if (imageUrl.startsWith("http")) "$imageUrl|$localMediaState|$retryTrigger" else null

internal fun shouldAutoRetryReaderImage(
    isError: Boolean,
    imageUrl: String,
    attemptCount: Int,
    maxAttempts: Int = 3
): Boolean =
    isError && imageUrl.startsWith("http") && attemptCount < maxAttempts

internal fun shouldRepairReaderImage(
    isError: Boolean,
    imageUrl: String,
    localMediaState: String,
    attemptCount: Int,
    maxAttempts: Int = 1
): Boolean =
    isError &&
        imageUrl.startsWith("http") &&
        localMediaState.isNotBlank() &&
        localMediaState != "missing" &&
        attemptCount < maxAttempts

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

    // Single, idempotent cache probe used to seed loading state and the loading UI choice.
    // Performed once per imageUrl on the Composition thread (down from 3–4 File ops previously).
    // Coil's HttpMediaCacheFetcher owns the authoritative disk check inside its own dispatcher.
    val isInitiallyCached = remember(imageUrl) {
        when {
            imageUrl.startsWith("file") -> true
            imageUrl.startsWith("http") ->
                readerViewModel.contentRepository.getLikelyMediaState(imageUrl) != "missing"
            else -> false
        }
    }

    // Hoist loading state so containerModifier can react to it, shrinking the container
    // once the image has loaded to avoid black gaps in long-strip (manhwa) mode.
    // Always start true so the 48dp minHeight reservation below holds until Coil delivers
    // a bitmap — without it LazyColumn measures cached items at 0 px before decode finishes,
    // breaking scroll-restore (ReaderContentArea snapshotFlow waits for itemSize > 0) and
    // forcing extra item composition at launch.
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

    // Paged manga: dim letterbox. Scroll mode: surface while loading to hide the dark theme bleeding
    // through the reserved aspect-ratio space, then transparent once decoded.
    // Skip the surface placeholder for already-cached images so the container does not flash
    // surface → transparent on every page during a long-strip scroll of a downloaded chapter.
    val effectiveBackground = when {
        enableZoom -> backgroundColor.copy(alpha = 0.5f)
        isLoadingHoisted && !isInitiallyCached -> MaterialTheme.colorScheme.surface
        else -> Color.Transparent
    }

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

    val context = LocalContext.current

    val showAnimatedLoadingUi = shouldUseAnimatedImageLoadingUi(
        enableZoom = enableZoom,
        isCached = isInitiallyCached
    )

    var retryTrigger by remember(imageUrl, pageUrl) { mutableStateOf(0L) }
    val localMediaState = remember(imageUrl, retryTrigger) {
        if (imageUrl.startsWith("http")) {
            readerViewModel.contentRepository.getLikelyMediaState(imageUrl)
        } else {
            ""
        }
    }
    // Keys are only the inputs that actually change the request. isInitiallyCached is stable
    // for the lifetime of the composition, so it does not need to be a key — capturing the
    // value once avoids the re-fetch loop that previously fired when the success handler
    // flipped a cached-state flag.
    val imageRequest = remember(imageUrl, pageUrl, retryTrigger, localMediaState) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .apply {
                if (imageUrl.startsWith("http")) {
                    readerImageRequestCacheKey(imageUrl, localMediaState, retryTrigger)?.let { cacheKey ->
                        memoryCacheKey(cacheKey)
                        diskCacheKey(cacheKey)
                    }
                    extras.set(ChapterPageUrlExtra, pageUrl)
                    httpHeaders(
                        NetworkHeaders.Builder()
                            .set("Referer", readerViewModel.contentRepository.getReferer(
                                readerImageRefererSource(imageUrl, pageUrl)
                            ))
                            .set("Accept", ImageDownloader.SUPPORTED_IMAGE_ACCEPT_HEADER)
                            .set("User-Agent", "Mozilla/5.0")
                            .build()
                    )
                }
                if (shouldSubsampleImage) {
                    // Width-only constraint. Long-strip manhwa pages can be 15000+ px tall;
                    // a `size(screenW, screenH)` FIT picks sampleSize by max(w-ratio, h-ratio),
                    // so a 900x15000 page becomes ~112x1875 → 9× upscale at display = pixelated.
                    // Width-only samples by width ratio alone, preserving native resolution
                    // along the scroll axis.
                    size(CoilSize(Dimension.Pixels(screenWidthPx), Dimension.Undefined))
                    scale(Scale.FIT)
                    precision(Precision.INEXACT)
                }
            }
            .crossfade(showAnimatedLoadingUi)
            .build()
    }
    var isError by remember(imageRequest) { mutableStateOf(false) }
    var autoRetryCount by remember(imageUrl, pageUrl) { mutableIntStateOf(0) }
    var repairRetryCount by remember(imageUrl, pageUrl) { mutableIntStateOf(0) }

    LaunchedEffect(isError, imageUrl, pageUrl, localMediaState, autoRetryCount, repairRetryCount) {
        if (shouldRepairReaderImage(isError, imageUrl, localMediaState, repairRetryCount)) {
            repairRetryCount += 1
            readerViewModel.repairVisibleImageNow(imageUrl, pageUrl)
            isError = false
            isLoadingHoisted = true
            retryTrigger = System.currentTimeMillis()
        } else if (shouldAutoRetryReaderImage(isError, imageUrl, autoRetryCount)) {
            val nextAttempt = autoRetryCount + 1
            kotlinx.coroutines.delay(750L * nextAttempt)
            autoRetryCount = nextAttempt
            isError = false
            isLoadingHoisted = true
            retryTrigger = System.currentTimeMillis()
        }
    }

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
                    isError = false
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
            var showSpinner by remember(imageRequest) { mutableStateOf(false) }
            LaunchedEffect(imageRequest) {
                kotlinx.coroutines.delay(200)
                showSpinner = true
            }
            if (showSpinner) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        if (isError) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(16.dp)
                    .clickable {
                        isError = false
                        isLoadingHoisted = true
                        retryTrigger = System.currentTimeMillis()
                    }
            ) {
                Text(
                    text = altText ?: "Image unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (imageUrl.startsWith("http")) "Tap to retry" else "Tap to reload",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
