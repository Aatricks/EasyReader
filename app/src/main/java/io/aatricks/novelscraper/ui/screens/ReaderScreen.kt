package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

/**
 * Main reading screen with drawer layout for library management.
 * 
 * Features:
 * - Left drawer with library content
 * - Scrollable main content area displaying text and images
 * - Automatic scroll position tracking
 * - Security scroll detection for chapter navigation
 * - Loading and error state handling
 * 
 * @param readerViewModel ViewModel managing reader state and content
 * @param libraryViewModel ViewModel managing library
 * @param onOpenFilePicker Callback to open file picker
 * @param modifier Modifier for customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Collect state from ViewModel
    val uiState by readerViewModel.uiState.collectAsState()
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Black,
                modifier = Modifier.width(320.dp)
            ) {
                LibraryDrawerContent(
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel,
                    onOpenFilePicker = onOpenFilePicker,
                    onCloseDrawer = {
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.content?.title ?: "Novel Scraper",
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open Library",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1A1A)
                    )
                )
            },
            containerColor = Color.Black
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    uiState.isLoading -> {
                        LoadingState()
                    }
                    uiState.error != null -> {
                        ErrorState(
                            error = uiState.error!!,
                            onRetry = { readerViewModel.retryLoad() }
                        )
                    }
                    uiState.content == null -> {
                        EmptyState(onOpenLibrary = {
                            scope.launch { drawerState.open() }
                        })
                    }
                    else -> {
                        ContentArea(
                            content = uiState.content!!,
                            readerViewModel = readerViewModel
                        )
                    }
                }
                
                // Progress indicator overlay
                if (uiState.scrollProgress > 0) {
                    LinearProgressIndicator(
                        progress = { uiState.scrollProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFF2C2C2C)
                    )
                }
            }
        }
    }
}

/**
 * Main content area displaying scrollable text and images.
 * Tracks scroll position for progress and chapter navigation.
 */
@Composable
private fun ContentArea(
    content: io.aatricks.novelscraper.data.model.ChapterContent,
    readerViewModel: ReaderViewModel
) {
    val listState = rememberLazyListState()
    
    // Track scroll position for progress
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (content.paragraphs.isNotEmpty()) {
            val totalItems = content.paragraphs.size
            val currentItem = listState.firstVisibleItemIndex
            
            // Calculate scroll offset
            val itemHeight = 100f // Approximate item height
            val maxScrollOffset = totalItems * itemHeight
            val currentScrollOffset = currentItem * itemHeight + listState.firstVisibleItemScrollOffset
            val viewportHeight = 800f // Approximate viewport height
            
            readerViewModel.updateScrollPosition(
                scrollOffset = currentScrollOffset,
                maxScrollOffset = maxScrollOffset,
                viewportHeight = viewportHeight
            )
        }
    }
    
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(content.paragraphs, key = { it.hashCode() }) { element ->
            when (element) {
                is ContentElement.Text -> {
                    Text(
                        text = element.content,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ContentElement.Image -> {
                    // Image rendering would go here
                    // For now, just show a placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.DarkGray)
                    )
                }
            }
        }
    }
}

/**
 * Loading state display
 */
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF4CAF50),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Loading content...",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * Error state display with retry option
 */
@Composable
private fun ErrorState(
    error: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Error",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Error loading content",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = error,
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Retry", color = Color.White)
            }
        }
    }
}

/**
 * Empty state display when no content is available
 */
@Composable
private fun EmptyState(onOpenLibrary: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MenuBook,
                contentDescription = "Empty",
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "No content available",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Add a novel from the library",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onOpenLibrary,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Open Library", color = Color.White)
            }
        }
    }
}

/**
 * UI state data class for reader screen
 */
data class ReaderUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentChapter: Int = 0,
    val totalChapters: Int = 0
)
