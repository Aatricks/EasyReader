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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.util.imageAspectRatio
import io.aatricks.novelscraper.ui.util.splitImageLayer
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import java.io.File
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
    side: ContentElement.Image.Side = ContentElement.Image.Side.FULL
) {
    val aspectRatioModifier = Modifier.imageAspectRatio(side, width, height)
    val imageModifier = Modifier
        .fillMaxWidth()
        .then(aspectRatioModifier)
        .splitImageLayer(side, width, height)

    if (imageUrl.startsWith("http")) {
        val context = LocalContext.current

        val cachedFile = remember(imageUrl) {
            readerViewModel.contentRepository.getCachedMediaFile(imageUrl)
        }

        val imageRequest = remember(imageUrl, pageUrl) {
            val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
            var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl
            
            // Special handling for MangaBat/Manganato images which often require specific referers
            if (referer.contains("mangabat") || referer.contains("manganato")) {
                referer = "https://manganato.com/"
            }
            
            val isCached = cachedFile.exists()

            ImageRequest.Builder(context)
                .data(if (isCached) cachedFile else imageUrl)
                .httpHeaders(NetworkHeaders.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build())
                .crossfade(!isCached) // Only crossfade if not cached
                .build()
        }
        var isError by remember(imageRequest) { mutableStateOf(false) }
        var isLoading by remember(imageRequest) { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(aspectRatioModifier)
                .background(backgroundColor.copy(alpha = 0.5f))
                .wrapContentHeight(),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = altText,
                modifier = imageModifier,
                contentScale = contentScale,
                onSuccess = { isLoading = false },
                onError = { 
                    isError = true
                    isLoading = false 
                }
            )

            if (isLoading && !cachedFile.exists()) {
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
        var isLoading by remember(imageUrl) { mutableStateOf(true) }
        var hasError by remember(imageUrl) { mutableStateOf(false) }
        
        LaunchedEffect(imageUrl) {
            try {
                isLoading = true; hasError = false
                val bytes = readerViewModel.contentRepository.getEpubImage(imageUrl)
                if (bytes != null) {
                    val bitmap = withContext(Dispatchers.IO) { 
                        val opt = android.graphics.BitmapFactory.Options()
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                    }
                    imageData = bitmap
                } else hasError = true
            } catch (e: Exception) { hasError = true } finally { isLoading = false }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(aspectRatioModifier)
                .background(backgroundColor), 
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp).padding(16.dp))
                hasError -> Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                imageData != null -> imageData?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(), 
                        contentDescription = altText, 
                        modifier = imageModifier, 
                        contentScale = contentScale
                    )
                }
            }
        }
    }
}