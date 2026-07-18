package io.aatricks.easyreader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.components.ReaderImageView
import io.aatricks.easyreader.ui.components.readerImageTileView
import io.aatricks.easyreader.ui.components.ZoomableBox
import io.aatricks.easyreader.ui.screens.reader.ReaderRenderItem
import io.aatricks.easyreader.ui.screens.reader.RenderPayload
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel

private const val CONTENT_TYPE_PLACEHOLDER = "placeholder"
private const val CONTENT_TYPE_PAGE = "page"
private const val CONTENT_TYPE_TEXT = "text"
private const val CONTENT_TYPE_IMAGE = "image"
private const val CONTENT_TYPE_IMAGE_GROUP = "image_group"

internal val localReaderPages = compositionLocalOf<List<ReaderPage>> { emptyList() }

internal fun readerContentType(element: ContentElement): String = when (element) {
    is ContentElement.Placeholder -> CONTENT_TYPE_PLACEHOLDER
    is ContentElement.PageContent -> CONTENT_TYPE_PAGE
    is ContentElement.Text -> CONTENT_TYPE_TEXT
    is ContentElement.Image -> CONTENT_TYPE_IMAGE
    is ContentElement.ImageGroup -> CONTENT_TYPE_IMAGE_GROUP
}

// Element-key generator lives in viewmodel layer so non-UI code (progress restore) shares it.
internal fun stableContentElementKey(pageUrl: String, index: Int, element: ContentElement): String {
    return io.aatricks.easyreader.ui.viewmodel.stableContentElementKey(pageUrl, index, element)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PagedReaderView(
    content: ChapterContent,
    pagerState: PagerState,
    uiState: ReaderViewModel.ReaderUiState,
    fontFamily: FontFamily,
    bgColor: Color,
    textColor: Color,
    readerViewModel: ReaderViewModel,
    isZoomable: Boolean
) {
    val pages = localReaderPages.current
    val zoomedPages = remember(content.url) { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed = zoomedPages[pagerState.currentPage] == true

    LaunchedEffect(isCurrentPageZoomed) {
        if (isCurrentPageZoomed) {
            readerViewModel.hideControls()
        }
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout = uiState.isRtl,
        userScrollEnabled = !uiState.showControls && !isCurrentPageZoomed,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val readerPage = pages.getOrNull(page)
        val onPageZoomChanged: (Boolean) -> Unit = { zoomed ->
            if (zoomed) {
                zoomedPages[page] = true
                readerViewModel.hideControls()
            } else {
                zoomedPages.remove(page)
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            readerPage?.let { generatedPage ->
                when (generatedPage) {
                    is ReaderPage.Text -> pagedTextContent(
                        page = generatedPage,
                        uiState = uiState,
                        fontFamily = fontFamily,
                        textColor = textColor
                    )

                    is ReaderPage.Element -> when (val el = generatedPage.element) {
                    is ContentElement.Placeholder -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = el.text,
                                color = textColor.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = uiState.fontSize.sp,
                                    fontFamily = fontFamily
                                )
                            )
                        }
                    }

                    is ContentElement.PageContent -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(uiState.margins.dp),
                            verticalArrangement = Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
                        ) {
                            el.elements.forEach { subElement ->
                                when (subElement) {
                                    is ContentElement.Text -> {
                                        Text(
                                            text = subElement.content,
                                            color = textColor,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = uiState.fontSize.sp,
                                                lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                                fontFamily = fontFamily
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    is ContentElement.Image -> {
                                        ReaderImageView(
                                            imageUrl = subElement.url,
                                            altText = subElement.altText,
                                            readerViewModel = readerViewModel,
                                            pageUrl = content.url,
                                            contentScale = ContentScale.Fit,
                                            backgroundColor = bgColor,
                                            width = subElement.width,
                                            height = subElement.height,
                                            side = subElement.side,
                                            enableZoom = isZoomable,
                                            zoomStateKey = "${content.url}_${page}_${subElement.url}_${subElement.side}",
                                            onZoomChanged = if (isZoomable) onPageZoomChanged else null,
                                            lockTapWhileZoomed = isZoomable,
                                            onDimensionsResolved = { url, w, h ->
                                                readerViewModel.persistImageDimensions(url, w, h)
                                            },
                                            onTap = null
                                        )
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }

                    is ContentElement.Text -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = el.content,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = uiState.fontSize.sp,
                                    lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                    fontFamily = fontFamily
                                ),
                                modifier = Modifier
                                    .padding(uiState.margins.dp)
                                    .fillMaxWidth()
                            )
                        }
                    }

                    is ContentElement.Image -> {
                        ReaderImageView(
                            imageUrl = el.url,
                            altText = el.altText,
                            readerViewModel = readerViewModel,
                            pageUrl = content.url,
                            contentScale = ContentScale.Fit,
                            backgroundColor = bgColor,
                            width = el.width,
                            height = el.height,
                            side = el.side,
                            enableZoom = isZoomable,
                            zoomStateKey = "${content.url}_${page}_${el.url}_${el.side}",
                            onZoomChanged = if (isZoomable) onPageZoomChanged else null,
                            lockTapWhileZoomed = isZoomable,
                            onDimensionsResolved = { url, w, h ->
                                readerViewModel.persistImageDimensions(url, w, h)
                            },
                            onTap = null
                        )
                    }

                    is ContentElement.ImageGroup -> {
                        PagedImageGroupView(
                            images = el.images,
                            pageUrl = content.url,
                            pageIndex = page,
                            backgroundColor = bgColor,
                            readerViewModel = readerViewModel,
                            enableZoom = isZoomable,
                            onPageZoomChanged = onPageZoomChanged
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun pagedTextContent(
    page: ReaderPage.Text,
    uiState: ReaderViewModel.ReaderUiState,
    fontFamily: FontFamily,
    textColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = uiState.margins.dp,
                vertical = uiState.verticalMargins.dp
            )
    ) {
        page.fragments.forEachIndexed { index, fragment ->
            if (index > 0) {
                Spacer(modifier = Modifier.height((uiState.fontSize * uiState.paragraphSpacing).dp))
            }
            Text(
                text = fragment.text,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = uiState.fontSize.sp,
                    lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                    fontFamily = fontFamily
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PagedImageGroupView(
    images: List<ContentElement.Image>,
    pageUrl: String,
    pageIndex: Int,
    backgroundColor: Color,
    readerViewModel: ReaderViewModel,
    enableZoom: Boolean,
    onPageZoomChanged: (Boolean) -> Unit
) {
    val scrollModifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())

    val contentColumn: @Composable () -> Unit = {
        Column(
            modifier = scrollModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically)
        ) {
            images.forEachIndexed { index, img ->
                ReaderImageView(
                    imageUrl = img.url,
                    altText = img.altText,
                    readerViewModel = readerViewModel,
                    pageUrl = pageUrl,
                    contentScale = ContentScale.Fit,
                    backgroundColor = backgroundColor,
                    width = img.width,
                    height = img.height,
                    side = img.side,
                    enableZoom = false,
                    dynamicHeight = true,
                    zoomStateKey = "${pageUrl}_${pageIndex}_group_$index",
                    onDimensionsResolved = { url, w, h ->
                        readerViewModel.persistImageDimensions(url, w, h)
                    },
                    onTap = null
                )
            }
        }
    }

    if (!enableZoom) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            contentColumn()
        }
        return
    }

    ZoomableBox(
        modifier = Modifier.fillMaxSize(),
        enableZoom = true,
        dynamicHeight = false,
        zoomStateKey = "${pageUrl}_${pageIndex}_group",
        onZoomChanged = onPageZoomChanged,
        lockTapWhileZoomed = true,
        onTap = null
    ) {
        contentColumn()
    }
}
