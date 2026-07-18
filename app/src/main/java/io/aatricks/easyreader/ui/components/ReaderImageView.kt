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
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
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

internal fun shouldUseLightweightImageContainer(enableZoom: Boolean): Boolean = !enableZoom

internal fun shouldUseAnimatedImageLoadingUi(enableZoom: Boolean, isCached: Boolean): Boolean =
    !shouldUseLightweightImageContainer(enableZoom) && !isCached

internal fun shouldSubsampleReaderImage(enableZoom: Boolean, dynamicHeight: Boolean): Boolean =
    !enableZoom && !dynamicHeight

internal fun readerImageRefererSource(imageUrl: String, pageUrl: String): String =
    pageUrl.takeIf { it.isNotBlank() } ?: imageUrl

internal fun readerImageRequestCacheKey(
    imageUrl: String,
    localMediaState: String,
    retryTrigger: Long
): String? =
    if (imageUrl.startsWith("http")) "$imageUrl|$localMediaState|$retryTrigger" else null

internal fun shouldLoadReaderImage(imageUrl: String, localMediaState: String?): Boolean =
    !imageUrl.startsWith("http") || localMediaState != null

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
    // Shared, per-URL resolved dimensions (survive this item being recycled). Lets a
    // re-entering item size itself on first composition instead of collapsing to the loading
    // placeholder and re-laying-out — the cause of stutter when dragging up/down quickly.
    // Reading through a per-URL State means a write for another image cannot invalidate this one.
    val sharedDimensions by remember(imageUrl) { readerViewModel.imageDimensionState(imageUrl) }
    val effectiveDimensions = effectiveImageDimensions(
        declaredWidth = width,
        declaredHeight = height,
        resolvedWidth = resolvedWidth.takeIf { it > 0 } ?: runtimeDimensions?.width ?: sharedDimensions?.first ?: 0,
        resolvedHeight = resolvedHeight.takeIf { it > 0 } ?: runtimeDimensions?.height ?: sharedDimensions?.second ?: 0
    )
    val effectiveWidth = effectiveDimensions?.width ?: 0
    val effectiveHeight = effectiveDimensions?.height ?: 0
    val aspectRatioModifier = Modifier.imageAspectRatio(side, effectiveWidth, effectiveHeight)
    val hasResolvedAspectRatio = effectiveDimensions != null

    // Load the cache-state snapshot off Main before creating the HTTP request. The repository
    // memoizes this probe, while Coil's HttpMediaCacheFetcher owns the authoritative disk check.
    var retryTrigger by remember(imageUrl, pageUrl) { mutableStateOf(0L) }
    var localMediaState by remember(imageUrl) {
        mutableStateOf<String?>(if (imageUrl.startsWith("http")) null else "")
    }
    LaunchedEffect(imageUrl, retryTrigger) {
        if (imageUrl.startsWith("http")) {
            localMediaState = readerViewModel.contentRepository.loadLikelyMediaState(imageUrl)
        }
    }
    val resolvedLocalMediaState = localMediaState.orEmpty()
    val isInitiallyCached = when {
        imageUrl.startsWith("file") -> true
        imageUrl.startsWith("http") -> localMediaState != null && localMediaState != "missing"
        else -> false
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

    // Keys are only the inputs that actually change the request. isInitiallyCached is stable
    // for the lifetime of the composition, so it does not need to be a key — capturing the
    // value once avoids the re-fetch loop that previously fired when the success handler
    // flipped a cached-state flag.
    val imageRequest = remember(imageUrl, pageUrl, retryTrigger, resolvedLocalMediaState) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .apply {
                if (imageUrl.startsWith("http")) {
                    readerImageRequestCacheKey(imageUrl, resolvedLocalMediaState, retryTrigger)?.let { cacheKey ->
                        memoryCacheKey(cacheKey)
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

    LaunchedEffect(isError, imageUrl, pageUrl, resolvedLocalMediaState, autoRetryCount, repairRetryCount) {
        if (shouldRepairReaderImage(isError, imageUrl, resolvedLocalMediaState, repairRetryCount)) {
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
            if (shouldLoadReaderImage(imageUrl, localMediaState)) {
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
                    // Kick off the GPU texture upload now, while the bitmap is decoded but (for
                    // compose-ahead items) not yet drawn. Reader bitmaps are software-backed, so
                    // otherwise HWUI uploads them synchronously on the RenderThread the first frame
                    // they scroll into view — measured at up to ~12ms, the cause of scroll
                    // micro-stutter. prepareToDraw() moves that upload onto a background thread.
                    (state.result.image as? coil3.BitmapImage)?.bitmap?.prepareToDraw()
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
                    .padding(EasyReaderSpacing.md)
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
                    modifier = Modifier.padding(top = EasyReaderSpacing.xxs)
                )
            }
        }
    }
}
