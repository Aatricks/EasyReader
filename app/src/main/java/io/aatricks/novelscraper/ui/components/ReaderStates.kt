package io.aatricks.novelscraper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color(0xFF4CAF50), modifier = Modifier.size(48.dp))
            Text(text = "Loading content...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun ErrorState(error: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(64.dp))
            Text(text = "Error loading content", style = MaterialTheme.typography.headlineSmall)
            Text(text = error, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Retry", color = Color.White) }
        }
    }
}

@Composable
fun EmptyState(onOpenLibrary: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(imageVector = Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Text(text = "No content available", style = MaterialTheme.typography.headlineSmall)
            Text(text = "Add a novel from the library", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenLibrary, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Open Library", color = Color.White) }
        }
    }
}
