package io.aatricks.easyreader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import androidx.compose.ui.graphics.graphicsLayer
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.ui.util.imageAspectRatio
import io.aatricks.easyreader.ui.util.splitImageLayer

@Composable
fun ContentRenderer(
    elements: List<ContentElement>,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    textColor: Color = Color.White,
    pageUrl: String = ""
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(EasyReaderSpacing.md),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
    ) {
        items(elements) { element ->
            when (element) {
                is ContentElement.Placeholder -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(element.heightDp.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = element.text,
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is ContentElement.PageContent -> {
                    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
                        element.elements.forEach { subElement ->
                            when (subElement) {
                                is ContentElement.Text -> {
                                    Text(
                                        text = subElement.content,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                is ContentElement.Image -> {
                                    AsyncImageElement(
                                        url = subElement.url, 
                                        altText = subElement.altText,
                                        side = subElement.side,
                                        width = subElement.width,
                                        height = subElement.height,
                                        pageUrl = pageUrl
                                    )
                                }
                                is ContentElement.ImageGroup -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                                        subElement.images.forEach { image ->
                                            AsyncImageElement(
                                                url = image.url, 
                                                altText = image.altText,
                                                side = image.side,
                                                width = image.width,
                                                height = image.height,
                                                pageUrl = pageUrl
                                            )
                                        }
                                    }
                                }
                                else -> {} // Should not happen for sub-elements in PDF
                            }
                        }
                    }
                }
                is ContentElement.Text -> {
                    Text(
                        text = element.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                is ContentElement.Image -> {
                    AsyncImageElement(
                        url = element.url, 
                        altText = element.altText,
                        side = element.side,
                        width = element.width,
                        height = element.height,
                        pageUrl = pageUrl
                    )
                }
                is ContentElement.ImageGroup -> {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        element.images.forEach { image ->
                            AsyncImageElement(
                                url = image.url, 
                                altText = image.altText,
                                side = image.side,
                                width = image.width,
                                height = image.height,
                                pageUrl = pageUrl
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AsyncImageElement(
    url: String, 
    altText: String?,
    side: ContentElement.Image.Side = ContentElement.Image.Side.FULL,
    width: Int = 0,
    height: Int = 0,
    pageUrl: String = ""
) {
    val context = LocalContext.current
    
    val imageRequest = remember(url, pageUrl) {
        val uri = try { java.net.URI(pageUrl) } catch (ex: Exception) { null }
        var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl
        
        if (referer.contains("mangabat")) {
            referer = "https://www.mangabats.com/"
        } else if (referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (!url.startsWith("file://")) {
                    httpHeaders(NetworkHeaders.Builder()
                        .set("Referer", referer)
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build())
                }
            }
            .crossfade(true)
            .build()
    }

    val painter = rememberAsyncImagePainter(model = imageRequest)

    val aspectRatioModifier = Modifier.imageAspectRatio(side, width, height)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(aspectRatioModifier)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = altText,
            modifier = Modifier
                .fillMaxWidth()
                .then(aspectRatioModifier)
                .splitImageLayer(side, width, height),
            contentScale = ContentScale.Fit
        )

        val state = painter.state.collectAsState().value
        if (state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        if (state is AsyncImagePainter.State.Error) {
            Text(
                text = altText ?: "Failed to load image",
                color = Color.Gray,
                modifier = Modifier.padding(EasyReaderSpacing.md)
            )
        }
    }
}
