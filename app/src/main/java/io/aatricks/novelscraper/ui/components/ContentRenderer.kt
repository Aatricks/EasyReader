package io.aatricks.novelscraper.ui.components

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
import io.aatricks.novelscraper.data.model.ContentElement

@Composable
fun ContentRenderer(
    elements: List<ContentElement>,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    textColor: Color = Color.White
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(elements) { element ->
            when (element) {
                is ContentElement.Text -> {
                    Text(
                        text = element.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                is ContentElement.Image -> {
                    AsyncImageElement(url = element.url, altText = element.altText)
                }
                is ContentElement.ImageGroup -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        element.images.forEach { image ->
                            AsyncImageElement(url = image.url, altText = image.altText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AsyncImageElement(url: String, altText: String?) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build()
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = altText,
            modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
