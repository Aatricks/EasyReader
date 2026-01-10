package io.aatricks.novelscraper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TopInfoBar(
    novelName: String,
    chapterTitle: String,
    isPagedMode: Boolean,
    isRtl: Boolean,
    onLibraryClick: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleRtl: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onLibraryClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "Open Library", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (novelName.isNotBlank()) {
                    Text(text = novelName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chapterTitle.isNotBlank()) {
                    Text(text = chapterTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onShowSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FormatSize, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onShowChapterList, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapter List", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onToggleMode, modifier = Modifier.size(40.dp)) {
                Icon(imageVector = if (isPagedMode) Icons.Filled.ViewCarousel else Icons.Filled.ViewStream, contentDescription = "Toggle Mode", tint = MaterialTheme.colorScheme.onSurface)
            }
            if (isPagedMode) {
                IconButton(onClick = onToggleRtl, modifier = Modifier.size(40.dp)) {
                    Text(text = if (isRtl) "RTL" else "LTR", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    progress: Float,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onProgressChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            var sliderValue by remember(progress) { mutableFloatStateOf(progress) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Progress", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                Text(text = "${sliderValue.toInt()}%", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onProgressChange(sliderValue) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = onPreviousClick, 
                    enabled = canNavigatePrevious, 
                    modifier = Modifier.weight(1f), 
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant, 
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Previous")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onNextClick, 
                    enabled = canNavigateNext, 
                    modifier = Modifier.weight(1f), 
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant, 
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Next")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
