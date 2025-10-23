package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import io.aatricks.novelscraper.data.model.ContentElement

/**
 * Content renderer component for displaying mixed text and image content.
 * 
 * Features:
 * - Renders text paragraphs with justified alignment
 * - Renders images using Coil image loading library
 * - Handles loading and error states for images
 * - Dark theme text colors for readability
 * - Proper spacing and formatting
 * - Responsive image sizing
 * 
 * @param element The content element to render (text or image)
 */
@Composable
fun ContentRenderer(element: ContentElement) {
    when (element) {
        is ContentElement.Text -> {
            TextContent(text = element.content)
        }
        is ContentElement.Image -> {
            ImageContent(imageUrl = element.url, description = element.description)
        }
    }
}

/**
 * Renders a text paragraph with proper formatting.
 * 
 * @param text The text content to display
 */
@Composable
private fun TextContent(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.6,
            textAlign = TextAlign.Justify
        ),
        color = Color(0xFFE0E0E0),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

/**
 * Renders an image with loading and error states.
 * Uses Coil for efficient image loading and caching.
 * 
 * @param imageUrl The URL of the image to load
 * @param description Optional description for accessibility
 */
@Composable
private fun ImageContent(imageUrl: String, description: String? = null) {
    val painter = rememberAsyncImagePainter(model = imageUrl)
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> {
                ImageLoadingState()
            }
            is AsyncImagePainter.State.Error -> {
                ImageErrorState()
            }
            else -> {
                Image(
                    painter = painter,
                    contentDescription = description ?: "Novel image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
    
    // Optional image description
    if (!description.isNullOrBlank()) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/**
 * Loading state for images
 */
@Composable
private fun ImageLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF4CAF50),
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Loading image...",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

/**
 * Error state for images that failed to load
 */
@Composable
private fun ImageErrorState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color(0xFF2C2C2C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Failed to load image",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Failed to load image",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

/**
 * Preview composable for ContentRenderer
 */
@Composable
fun ContentRendererPreview() {
    Column(
        modifier = Modifier
            .background(Color.Black)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Text content preview
        ContentRenderer(
            element = ContentElement.Text(
                content = "This is a sample paragraph of text that demonstrates the justified " +
                        "text alignment and proper formatting. The text should wrap naturally " +
                        "and maintain good readability with appropriate line height and spacing."
            )
        )
        
        // Image content preview
        ContentRenderer(
            element = ContentElement.Image(
                url = "https://example.com/image.jpg",
                description = "Sample image description"
            )
        )
    }
}

/**
 * Renders a list of content elements efficiently.
 * Use this for batch rendering in LazyColumn.
 * 
 * @param elements List of content elements to render
 */
@Composable
fun ContentList(elements: List<ContentElement>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        elements.forEach { element ->
            ContentRenderer(element = element)
        }
    }
}
