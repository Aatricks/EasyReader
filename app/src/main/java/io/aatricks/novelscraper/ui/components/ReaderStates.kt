package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingState() {
    ReaderStatePanel(
        icon = Icons.Default.AutoStories,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "Loading chapter",
        body = "Preparing your chapter. If you've read it before, we'll restore your place.",
        action = {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
fun ErrorState(error: String, onRetry: () -> Unit) {
    ReaderStatePanel(
        icon = Icons.Default.WarningAmber,
        iconTint = MaterialTheme.colorScheme.error,
        title = "Reader couldn’t load this chapter",
        body = error,
        action = {
            FilledTonalButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    )
}

@Composable
fun EmptyState(onOpenLibrary: () -> Unit) {
    ReaderStatePanel(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "Pick something to read",
        body = "Open your library to resume where you left off, open the latest chapter, or start something new.",
        action = {
            FilledTonalButton(onClick = onOpenLibrary) {
                Text("Open Library")
            }
        }
    )
}

@Composable
private fun ReaderStatePanel(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    action: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (action != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    action()
                }
            }
        }
    }
}
