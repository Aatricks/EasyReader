package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aatricks.novelscraper.data.model.ChapterContent
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.components.ReaderImageView
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel

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
): Unit {
    HorizontalPager(
        state = pagerState,
        reverseLayout = uiState.isRtl,
        userScrollEnabled = !uiState.showControls,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val element = content.paragraphs.getOrNull(page)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            element?.let { el ->
                when (el) {
                    is ContentElement.Placeholder -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { readerViewModel.toggleControls() }
                                ),
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
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { readerViewModel.toggleControls() }
                                )
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
                                            onTap = { readerViewModel.toggleControls() }
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
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { readerViewModel.toggleControls() }
                                )
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
                            onTap = { readerViewModel.toggleControls() }
                        )
                    }

                    is ContentElement.ImageGroup -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically)
                        ) {
                            el.images.forEach { img ->
                                ReaderImageView(
                                    imageUrl = img.url,
                                    altText = img.altText,
                                    readerViewModel = readerViewModel,
                                    pageUrl = content.url,
                                    contentScale = ContentScale.Fit,
                                    backgroundColor = bgColor,
                                    width = img.width,
                                    height = img.height,
                                    side = img.side,
                                    enableZoom = isZoomable,
                                    dynamicHeight = true,
                                    onTap = { readerViewModel.toggleControls() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScrollingReaderView(
    content: ChapterContent,
    listState: LazyListState,
    uiState: ReaderViewModel.ReaderUiState,
    isManhwa: Boolean,
    fontFamily: FontFamily,
    bgColor: Color,
    textColor: Color,
    readerViewModel: ReaderViewModel
): Unit {
    LaunchedEffect(uiState.targetScrollPosition, listState.canScrollForward) {
        if (uiState.targetScrollPosition == 100f && content.paragraphs.isNotEmpty() && listState.canScrollForward) {
            listState.scrollToItem(content.paragraphs.size - 1, 10000000)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = if (isManhwa) {
            Arrangement.spacedBy(0.dp)
        } else {
            Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
        }
    ) {
        itemsIndexed(
            content.paragraphs,
            key = { index, _ -> "${content.url}_$index" }
        ) { _, element ->
            when (element) {
                is ContentElement.Placeholder -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(element.heightDp.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = element.text,
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
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            )
                            .padding(horizontal = uiState.margins.dp),
                        verticalArrangement = Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
                    ) {
                        element.elements.forEach { subElement ->
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
                                        enableZoom = false,
                                        dynamicHeight = false,
                                        onTap = { readerViewModel.toggleControls() }
                                    )
                                }

                                else -> Unit
                            }
                        }
                    }
                }

                is ContentElement.Text -> {
                    Text(
                        text = element.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = uiState.fontSize.sp,
                            lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                            fontFamily = fontFamily
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = uiState.margins.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            )
                    )
                }

                is ContentElement.Image -> {
                    ReaderImageView(
                        imageUrl = element.url,
                        altText = element.altText,
                        readerViewModel = readerViewModel,
                        pageUrl = content.url,
                        contentScale = ContentScale.Fit,
                        backgroundColor = bgColor,
                        width = element.width,
                        height = element.height,
                        side = element.side,
                        enableZoom = false,
                        dynamicHeight = false,
                        onTap = { readerViewModel.toggleControls() }
                    )
                }

                is ContentElement.ImageGroup -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        element.images.forEach { img ->
                            ReaderImageView(
                                imageUrl = img.url,
                                altText = img.altText,
                                readerViewModel = readerViewModel,
                                pageUrl = content.url,
                                contentScale = ContentScale.Fit,
                                backgroundColor = bgColor,
                                width = img.width,
                                height = img.height,
                                side = img.side,
                                enableZoom = false,
                                dynamicHeight = false,
                                onTap = { readerViewModel.toggleControls() }
                            )
                        }
                    }
                }
            }
        }
    }
}
