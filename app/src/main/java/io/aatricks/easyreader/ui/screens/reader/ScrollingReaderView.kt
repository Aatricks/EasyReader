package io.aatricks.easyreader.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.components.ReaderImageView
import io.aatricks.easyreader.ui.components.readerImageTileView
import io.aatricks.easyreader.ui.screens.readerContentType
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel

private const val SCROLL_TO_END_OFFSET = 10_000_000
private const val PLACEHOLDER_TEXT_ALPHA = 0.5f
private const val CONTENT_TYPE_IMAGE = "image"
private const val TARGET_END_PERCENT = 100f

internal data class ScrollingReaderState(
    val content: ChapterContent,
    val renderItems: List<ReaderRenderItem>,
    val listState: LazyListState,
    val uiState: ReaderViewModel.ReaderUiState,
    val isManhwa: Boolean,
    val fontFamily: FontFamily,
    val backgroundColor: Color,
    val textColor: Color,
    val readerViewModel: ReaderViewModel
) {
    val contentUrl: String = content.url
}

@Composable
internal fun scrollingReaderView(state: ScrollingReaderState) = with(state) {
    LaunchedEffect(uiState.targetScrollPosition, listState.canScrollForward) {
        val shouldScrollToEnd = uiState.targetScrollPosition == TARGET_END_PERCENT &&
            renderItems.isNotEmpty() && listState.canScrollForward
        if (shouldScrollToEnd) {
            listState.scrollToItem(renderItems.lastIndex, SCROLL_TO_END_OFFSET)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = if (isManhwa) PaddingValues(0.dp) else PaddingValues(vertical = uiState.verticalMargins.dp),
        verticalArrangement = if (isManhwa) {
            Arrangement.spacedBy(0.dp)
        } else {
            Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
        }
    ) {
        itemsIndexed(
            renderItems,
            key = { _, item -> item.key },
            contentType = { _, item ->
                when (val payload = item.payload) {
                    is RenderPayload.Element -> readerContentType(payload.element)
                    is RenderPayload.Tile -> CONTENT_TYPE_IMAGE
                }
            }
        ) { _, item -> scrollingReaderItem(item, state) }
    }
}

@Composable
private fun scrollingReaderItem(item: ReaderRenderItem, state: ScrollingReaderState) {
    when (val payload = item.payload) {
        is RenderPayload.Tile -> readerImageTileView(
            tile = payload,
            backgroundColor = state.backgroundColor,
            onTap = { state.readerViewModel.toggleControls() }
        )
        is RenderPayload.Element -> when (val element = payload.element) {
            is ContentElement.Placeholder -> scrollingPlaceholder(element, state)
            is ContentElement.PageContent -> scrollingPageContent(element, state)
            is ContentElement.Text -> scrollingText(element, state, addHorizontalPadding = true)
            is ContentElement.Image -> scrollingImage(element, state)
            is ContentElement.ImageGroup -> scrollingImageGroup(element, state)
        }
    }
}

@Composable
private fun scrollingPlaceholder(element: ContentElement.Placeholder, state: ScrollingReaderState) {
    Box(
        modifier = Modifier.fillMaxWidth().height(element.heightDp.dp).readerTap(state.readerViewModel),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = element.text,
            color = state.textColor.copy(alpha = PLACEHOLDER_TEXT_ALPHA),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = state.uiState.fontSize.sp,
                fontFamily = state.fontFamily
            )
        )
    }
}

@Composable
private fun scrollingPageContent(element: ContentElement.PageContent, state: ScrollingReaderState) {
    Column(
        modifier = Modifier.fillMaxWidth().readerTap(state.readerViewModel)
            .padding(horizontal = state.uiState.margins.dp),
        verticalArrangement = Arrangement.spacedBy((state.uiState.fontSize * state.uiState.paragraphSpacing).dp)
    ) {
        element.elements.forEach { child ->
            when (child) {
                is ContentElement.Text -> scrollingText(child, state, addHorizontalPadding = false)
                is ContentElement.Image -> scrollingImage(child, state)
                else -> Unit
            }
        }
    }
}

@Composable
private fun scrollingText(element: ContentElement.Text, state: ScrollingReaderState, addHorizontalPadding: Boolean) {
    val modifier = if (addHorizontalPadding) {
        Modifier.fillMaxWidth().padding(horizontal = state.uiState.margins.dp).readerTap(state.readerViewModel)
    } else {
        Modifier.fillMaxWidth()
    }
    Text(
        text = element.content,
        color = state.textColor,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = state.uiState.fontSize.sp,
            lineHeight = (state.uiState.fontSize * state.uiState.lineHeight).sp,
            fontFamily = state.fontFamily
        ),
        modifier = modifier
    )
}

@Composable
private fun scrollingImage(element: ContentElement.Image, state: ScrollingReaderState) {
    ReaderImageView(
        imageUrl = element.url,
        altText = element.altText,
        readerViewModel = state.readerViewModel,
        pageUrl = state.contentUrl,
        contentScale = ContentScale.Fit,
        backgroundColor = state.backgroundColor,
        width = element.width,
        height = element.height,
        side = element.side,
        enableZoom = false,
        dynamicHeight = false,
        onDimensionsResolved = state.readerViewModel::persistImageDimensions,
        onTap = { state.readerViewModel.toggleControls() }
    )
}

@Composable
private fun scrollingImageGroup(element: ContentElement.ImageGroup, state: ScrollingReaderState) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        element.images.forEach { scrollingImage(it, state) }
    }
}

@Composable
private fun Modifier.readerTap(readerViewModel: ReaderViewModel): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = readerViewModel::toggleControls
)
