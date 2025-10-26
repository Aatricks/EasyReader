package io.aatricks.novelscraper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

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
                            readerViewModel = readerViewModel,
                            onLibraryClick = { scope.launch { drawerState.open() } }
                        )
                    }
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
    readerViewModel: ReaderViewModel,
    onLibraryClick: () -> Unit
) {
    val listState = rememberLazyListState()
    val uiState by readerViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    
    // Remember whether we've applied a restored scroll for this content URL
    val appliedRestore = remember(content.url) { mutableStateOf(false) }
    
    // If there's a saved percent scroll position in the ViewModel, apply it once when content loads.
    LaunchedEffect(content.url, uiState.scrollPosition) {
        if (!appliedRestore.value && uiState.scrollPosition > 0f && content.paragraphs.isNotEmpty()) {
            // Map percent -> item index + offset
            val totalItems = content.paragraphs.size
            val percent = uiState.scrollPosition.coerceIn(0f, 100f) / 100f

            // Use same approximations as progress calculation below
            val itemHeight = 100f
            val targetPosition = percent * totalItems
            val index = targetPosition.toInt().coerceIn(0, totalItems - 1)
            val offsetFraction = targetPosition - index
            val pixelOffset = (offsetFraction * itemHeight).toInt()

            // Programmatic scroll to approximate position
            try {
                listState.scrollToItem(index, pixelOffset)
            } catch (_: Exception) {
                // ignore failures to avoid crashing UI
            }

            appliedRestore.value = true
        }
    }

    // Track scroll position for progress (keeps same logic as before)
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
    
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            readerViewModel.toggleControls()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        // Hide controls on any vertical swipe
                        if (abs(dragAmount) > 10f) {
                            readerViewModel.hideControls()
                        }
                    }
                },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        // Use indexed keys that include the chapter URL to ensure uniqueness across
        // chapters and prevent IllegalArgumentException when the same element values
        // (and thus identical hashCodes) appear in multiple chapters.
        itemsIndexed(content.paragraphs, key = { index: Int, _: ContentElement -> "${content.url}_$index" }) { index: Int, element: ContentElement ->
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
                    EpubImageView(
                        imageUrl = element.url,
                        altText = element.altText,
                        readerViewModel = readerViewModel
                    )
                }
            }
        }
        
        // Navigation buttons at the end of content
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Previous chapter button
                if (content.hasPreviousChapter()) {
                    Button(
                        onClick = { readerViewModel.navigateToPreviousChapter() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A1A1A),
                            contentColor = Color.White
                        )
                    ) {
                        Text("← Previous Chapter")
                    }
                }
                
                // Next chapter button
                if (content.hasNextChapter()) {
                    Button(
                        onClick = { readerViewModel.navigateToNextChapter() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A1A1A),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Next Chapter →")
                    }
                }
            }
        }
    }
        
        // Animated top bar with novel name and chapter
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopInfoBar(
                novelName = uiState.novelName,
                chapterTitle = uiState.chapterTitle,
                onLibraryClick = onLibraryClick
            )
        }
        
        // Animated bottom navigation bar
        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomNavigationBar(
                progress = uiState.scrollProgress,
                canNavigatePrevious = uiState.canNavigatePrevious,
                canNavigateNext = uiState.canNavigateNext,
                onPreviousClick = { readerViewModel.navigateToPreviousChapter() },
                onNextClick = { readerViewModel.navigateToNextChapter() }
            )
        }
    }
}

/**
 * Top info bar with novel name and chapter title
 */
@Composable
private fun TopInfoBar(
    novelName: String,
    chapterTitle: String,
    onLibraryClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        color = Color(0xE6000000), // Semi-transparent black
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Library button
            IconButton(
                onClick = onLibraryClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Open Library",
                    tint = Color.White
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Novel name and chapter title
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Novel name
                if (novelName.isNotBlank()) {
                    Text(
                        text = novelName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Chapter title
                if (chapterTitle.isNotBlank()) {
                    Text(
                        text = chapterTitle,
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Bottom navigation bar with chapter navigation and progress
 */
@Composable
private fun BottomNavigationBar(
    progress: Int,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        color = Color(0xE6000000), // Semi-transparent black
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Progress",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "$progress%",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF4CAF50),
                trackColor = Color(0xFF2A2A2A)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Previous button
                Button(
                    onClick = onPreviousClick,
                    enabled = canNavigatePrevious,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF0D0D0D),
                        disabledContentColor = Color(0xFF555555)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Previous Chapter",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Previous")
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Next button
                Button(
                    onClick = onNextClick,
                    enabled = canNavigateNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF0D0D0D),
                        disabledContentColor = Color(0xFF555555)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = "Next Chapter",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Display EPUB image
 */
@Composable
private fun EpubImageView(
    imageUrl: String,
    altText: String?,
    readerViewModel: ReaderViewModel
) {
    var imageData by remember(imageUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(imageUrl) { mutableStateOf(true) }
    var hasError by remember(imageUrl) { mutableStateOf(false) }
    
    LaunchedEffect(imageUrl) {
        try {
            isLoading = true
            hasError = false
            val bytes = readerViewModel.contentRepository.getEpubImage(imageUrl)
            if (bytes != null) {
                imageData = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                hasError = true
            }
        } catch (e: Exception) {
            hasError = true
        } finally {
            isLoading = false
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = Color.Gray,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(16.dp)
                )
            }
            hasError -> {
                Text(
                    text = altText ?: "Image unavailable",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            imageData != null -> {
                androidx.compose.foundation.Image(
                    bitmap = imageData!!.asImageBitmap(),
                    contentDescription = altText,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
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
