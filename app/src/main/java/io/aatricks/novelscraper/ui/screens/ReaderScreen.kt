package io.aatricks.novelscraper.ui.screens

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.*
import androidx.activity.compose.BackHandler
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import io.aatricks.novelscraper.ui.screens.explore.ExploreScreen
import io.aatricks.novelscraper.data.repository.ExploreRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showExplore by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exploreRepository = remember { ExploreRepository(context) }
    
    var showCloudflareWebView by remember { mutableStateOf(false) }
    var cloudflareUrl by remember { mutableStateOf("") }

    var showChapterList by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()

    // Collect state from ViewModel
    val uiState by readerViewModel.uiState.collectAsState()

    // Handle back button for drawer
    BackHandler(enabled = drawerState.isOpen && !showExplore) {
        scope.launch { drawerState.close() }
    }

    // Handle back button to open drawer if closed and not in explore
    BackHandler(enabled = !drawerState.isOpen && !showExplore && uiState.content != null) {
        scope.launch { drawerState.open() }
    }
    
    // Check for Cloudflare/403 errors
    LaunchedEffect(uiState.error) {
        if (uiState.error?.contains("403") == true || uiState.error?.contains("503") == true) {
            cloudflareUrl = uiState.content?.url ?: ""
            if (cloudflareUrl.startsWith("http")) {
                showCloudflareWebView = true
            }
        }
    }

    // Manage Status Bar Visibility
    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    LaunchedEffect(uiState.showControls) {
        if (window != null) {
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (!uiState.showControls) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    if (showCloudflareWebView) {
        AlertDialog(
            onDismissRequest = { showCloudflareWebView = false },
            title = { Text("Solve Challenge") },
            text = {
                Column {
                    Text("Please solve the challenge to continue reading.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                        AndroidView(factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                    }
                                }
                                loadUrl(cloudflareUrl)
                            }
                        })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { 
                    showCloudflareWebView = false
                    readerViewModel.retryLoad()
                }) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudflareWebView = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExplore) {
        ExploreScreen(
            exploreRepository = exploreRepository,
            libraryViewModel = libraryViewModel,
            onNavigateBack = { showExplore = false }
        )
    } else {
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
                        },
                        onExploreClick = {
                            scope.launch { drawerState.close() }
                            showExplore = true
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
                                libraryViewModel = libraryViewModel,
                                onLibraryClick = { scope.launch { drawerState.open() } },
                                onShowChapterList = { showChapterList = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showChapterList) {
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedChapterUrls = remember { mutableStateListOf<String>() }
        // true if selecting downloaded chapters for deletion, false if selecting source chapters for download
        var isDeleteMode by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { 
                showChapterList = false
                isSelectionMode = false
                selectedChapterUrls.clear()
            },
            sheetState = bottomSheetState,
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color.White
        ) {
            val libraryItemsInGroup = libraryViewModel.uiState.value.groupedItems[uiState.baseTitle] ?: emptyList()
            val downloadedUrls = libraryItemsInGroup.map { it.url }.toSet()
            
            val allChapters = uiState.fullChapterList.ifEmpty {
                libraryItemsInGroup.map { 
                    io.aatricks.novelscraper.data.model.ChapterInfo(it.currentChapter.ifBlank { it.title }, it.url)
                }.reversed()
            }

            val filteredChapters = if (isSelectionMode) {
                if (isDeleteMode) {
                    allChapters.filter { it.url in downloadedUrls }
                } else {
                    allChapters.filter { it.url !in downloadedUrls }
                }
            } else {
                allChapters
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSelectionMode) {
                            if (isDeleteMode) "Delete Chapters (${selectedChapterUrls.size})" 
                            else "Download Chapters (${selectedChapterUrls.size})"
                        } else "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )

                    if (isSelectionMode) {
                        Row {
                            IconButton(onClick = {
                                if (isDeleteMode) {
                                    // Delete selected chapters in batch
                                    val idsToRemove = selectedChapterUrls.mapNotNull { url ->
                                        libraryItemsInGroup.find { it.url == url }?.id
                                    }.toSet()
                                    if (idsToRemove.isNotEmpty()) {
                                        libraryViewModel.removeItems(idsToRemove)
                                    }
                                } else {
                                    // Download selected chapters
                                    val chaptersToDownload = selectedChapterUrls.mapNotNull { url ->
                                        allChapters.find { it.url == url }
                                    }
                                    if (chaptersToDownload.isNotEmpty()) {
                                        libraryViewModel.addChapters(
                                            chapters = chaptersToDownload,
                                            baseTitle = uiState.baseTitle,
                                            baseNovelUrl = uiState.baseNovelUrl,
                                            sourceName = uiState.sourceName
                                        )
                                    }
                                }
                                isSelectionMode = false
                                selectedChapterUrls.clear()
                            }) {
                                Icon(
                                    imageVector = if (isDeleteMode) Icons.Default.Delete else Icons.Default.Download,
                                    contentDescription = if (isDeleteMode) "Delete" else "Download",
                                    tint = if (isDeleteMode) Color.Red else Color(0xFF4CAF50)
                                )
                            }
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedChapterUrls.clear()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray)
                            }
                        }
                    }
                }
                
                if (uiState.isChaptersLoading && uiState.fullChapterList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF4CAF50))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                        items(filteredChapters) { chapter ->
                            val isDownloaded = chapter.url in downloadedUrls
                            val isSelected = chapter.url in selectedChapterUrls

                            ListItem(
                                headlineContent = { 
                                    Text(
                                        text = chapter.title,
                                        color = when {
                                            isSelected -> Color(0xFF90CAF9)
                                            chapter.url == uiState.content?.url -> Color(0xFF4CAF50)
                                            else -> Color.White
                                        }
                                    ) 
                                },
                                trailingContent = {
                                    if (!isSelectionMode && isDownloaded) {
                                        Icon(Icons.Default.Check, contentDescription = "Downloaded", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    } else if (isSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedChapterUrls.add(chapter.url)
                                                else selectedChapterUrls.remove(chapter.url)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4CAF50))
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (chapter.url in selectedChapterUrls) selectedChapterUrls.remove(chapter.url)
                                                else selectedChapterUrls.add(chapter.url)
                                            } else {
                                                scope.launch {
                                                    bottomSheetState.hide()
                                                    showChapterList = false
                                                    readerViewModel.navigateToChapter(chapter.url, chapter.title)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                isSelectionMode = true
                                                isDeleteMode = isDownloaded
                                                selectedChapterUrls.add(chapter.url)
                                            }
                                        }
                                    ),
                                colors = ListItemDefaults.colors(
                                    containerColor = if (isSelected) Color(0xFF1E3A8A).copy(alpha = 0.3f) else Color.Transparent
                                )
                            )
                            HorizontalDivider(color = Color.DarkGray)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Main content area displaying scrollable text and images.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentArea(
    content: io.aatricks.novelscraper.data.model.ChapterContent,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onLibraryClick: () -> Unit,
    onShowChapterList: () -> Unit
) {
    val listState = rememberLazyListState()
    val uiState by readerViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f
    ) {
        content.paragraphs.size
    }

    val appliedRestore = remember(content.url) { mutableStateOf(false) }

    // Handle initial restore AND manual seek requests
    LaunchedEffect(content.url, uiState.seekTrigger) {
        if (content.paragraphs.isNotEmpty()) {
            val isInitialRestore = !appliedRestore.value
            val isManualSeek = uiState.seekTrigger > 0L
            
            if (isInitialRestore || isManualSeek) {
                if (uiState.isPagedMode) {
                    val page = if (isInitialRestore) {
                        uiState.scrollIndex.coerceIn(0, content.paragraphs.size - 1)
                    } else {
                        ((uiState.scrollPosition / 100f) * content.paragraphs.size).toInt()
                            .coerceIn(0, content.paragraphs.size - 1)
                    }
                    pagerState.scrollToPage(page)
                } else {
                    if (isInitialRestore && (uiState.scrollIndex > 0 || uiState.scrollOffset > 0)) {
                        try {
                            listState.scrollToItem(uiState.scrollIndex, uiState.scrollOffset)
                        } catch (_: Exception) {}
                    } else {
                        val totalItems = content.paragraphs.size
                        val percent = uiState.scrollPosition.coerceIn(0f, 100f) / 100f
                        val itemHeight = 100f // estimated
                        val targetPosition = percent * totalItems
                        val index = targetPosition.toInt().coerceIn(0, totalItems - 1)
                        val offsetFraction = targetPosition - index
                        val pixelOffset = (offsetFraction * itemHeight).toInt()

                        try {
                            listState.scrollToItem(index, pixelOffset)
                        } catch (_: Exception) {}
                    }
                }
                if (isInitialRestore) appliedRestore.value = true
            }
        }
    }

    if (uiState.isPagedMode) {
        LaunchedEffect(pagerState.currentPage) {
            val totalItems = content.paragraphs.size
            val currentItem = pagerState.currentPage
            val progress = if (totalItems > 0) ((currentItem.toFloat() / totalItems) * 100f).coerceIn(0f, 100f) else 0f
            
            readerViewModel.updateScrollPosition(
                scrollOffset = progress,
                maxScrollOffset = 100f,
                viewportHeight = 1f,
                index = currentItem,
                offset = 0
            )
        }
    } else {
        LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
            if (content.paragraphs.isNotEmpty()) {
                val totalItems = content.paragraphs.size
                val currentItem = listState.firstVisibleItemIndex
                val itemHeight = 100f
                val maxScrollOffset = totalItems * itemHeight
                val currentScrollOffset = currentItem * itemHeight + listState.firstVisibleItemScrollOffset
                val viewportHeight = 800f

                readerViewModel.updateScrollPosition(
                    scrollOffset = currentScrollOffset,
                    maxScrollOffset = maxScrollOffset,
                    viewportHeight = viewportHeight,
                    index = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset
                )
            }
        }
    }

    val isManhwa = remember(content) {
        content.getImageCount() > content.getTextCount() && content.getImageCount() > 2
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isPagedMode) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = uiState.isRtl,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { readerViewModel.toggleControls() })
                    }
            ) { page ->
                val element = content.paragraphs.getOrNull(page)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (element != null) {
                        when (element) {
                            is ContentElement.Text -> {
                                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    Text(
                                        text = element.content,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                                    )
                                }
                            }
                            is ContentElement.Image -> {
                                ReaderImageView(
                                    imageUrl = element.url,
                                    altText = element.altText,
                                    readerViewModel = readerViewModel,
                                    pageUrl = content.url,
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { readerViewModel.toggleControls() })
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (abs(dragAmount) > 10f) readerViewModel.hideControls()
                        }
                    },
                contentPadding = if (isManhwa) PaddingValues(0.dp) else PaddingValues(16.dp),
                verticalArrangement = if (isManhwa) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(
                    content.paragraphs,
                    key = { index: Int, _: ContentElement -> "${content.url}_$index" }) { index: Int, element: ContentElement ->
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
                            ReaderImageView(
                                imageUrl = element.url,
                                altText = element.altText,
                                readerViewModel = readerViewModel,
                                pageUrl = content.url,
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (content.hasPreviousChapter()) {
                            Button(
                                onClick = { readerViewModel.navigateToPreviousChapter() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A), contentColor = Color.White)
                            ) {
                                Text("← Previous Chapter")
                            }
                        }
                        if (content.hasNextChapter()) {
                            Button(
                                onClick = { readerViewModel.navigateToNextChapter() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A), contentColor = Color.White)
                            ) {
                                Text("Next Chapter →")
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopInfoBar(
                novelName = uiState.novelName,
                chapterTitle = uiState.chapterTitle,
                isPagedMode = uiState.isPagedMode,
                isRtl = uiState.isRtl,
                onLibraryClick = onLibraryClick,
                onToggleMode = { readerViewModel.toggleReadingMode() },
                onToggleRtl = { readerViewModel.toggleRtl() },
                onShowChapterList = onShowChapterList
            )
        }

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
                onNextClick = { readerViewModel.navigateToNextChapter() },
                onProgressChange = { readerViewModel.seekToProgress(it) }
            )
        }
    }
}

@Composable
private fun TopInfoBar(
    novelName: String,
    chapterTitle: String,
    isPagedMode: Boolean,
    isRtl: Boolean,
    onLibraryClick: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleRtl: () -> Unit,
    onShowChapterList: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xE6000000),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onLibraryClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "Open Library", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (novelName.isNotBlank()) {
                    Text(text = novelName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chapterTitle.isNotBlank()) {
                    Text(text = chapterTitle, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onShowChapterList, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.List, contentDescription = "Chapter List", tint = Color.White)
            }
            IconButton(onClick = onToggleMode, modifier = Modifier.size(40.dp)) {
                Icon(imageVector = if (isPagedMode) Icons.Filled.ViewCarousel else Icons.Filled.ViewStream, contentDescription = "Toggle Mode", tint = Color.White)
            }
            if (isPagedMode) {
                IconButton(onClick = onToggleRtl, modifier = Modifier.size(40.dp)) {
                    Text(text = if (isRtl) "RTL" else "LTR", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    progress: Int,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onProgressChange: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xE6000000),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            var sliderValue by remember(progress) { mutableFloatStateOf(progress.toFloat()) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Progress", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text(text = "${sliderValue.toInt()}%", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onProgressChange(sliderValue.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF4CAF50),
                    activeTrackColor = Color(0xFF4CAF50),
                    inactiveTrackColor = Color(0xFF2A2A2A)
                ),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = onPreviousClick, enabled = canNavigatePrevious, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A), contentColor = Color.White)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Previous")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = onNextClick, enabled = canNavigateNext, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A), contentColor = Color.White)) {
                    Text("Next")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ReaderImageView(
    imageUrl: String,
    altText: String?,
    readerViewModel: ReaderViewModel,
    pageUrl: String,
    contentScale: ContentScale = ContentScale.FillWidth
) {
    if (imageUrl.startsWith("http")) {
        val context = LocalContext.current
        
        // Check for cached image file
        val cachedFile = remember(imageUrl) { 
            readerViewModel.contentRepository.getCachedMediaFile(imageUrl) 
        }
        
        val imageRequest = remember(imageUrl, pageUrl) {
            val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
            val referer = if (uri != null) "${uri.scheme}://${uri.host}/" else pageUrl
            
            ImageRequest.Builder(context)
                .data(if (cachedFile.exists()) cachedFile else imageUrl)
                .addHeader("Referer", referer)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .crossfade(true)
                .build()
        }
        SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = altText,
            modifier = Modifier.fillMaxWidth(),
            contentScale = contentScale,
            loading = { Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp)) } },
            error = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
        )
    } else {
        var imageData by remember(imageUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var isLoading by remember(imageUrl) { mutableStateOf(true) }
        var hasError by remember(imageUrl) { mutableStateOf(false) }
        LaunchedEffect(imageUrl) {
            try {
                isLoading = true; hasError = false
                val bytes = readerViewModel.contentRepository.getEpubImage(imageUrl)
                if (bytes != null) {
                    val bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    imageData = bitmap
                } else hasError = true
            } catch (e: Exception) { hasError = true } finally { isLoading = false }
        }
        Box(modifier = Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                isLoading -> CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(32.dp).padding(16.dp))
                hasError -> Text(text = altText ?: "Image unavailable", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                imageData != null -> androidx.compose.foundation.Image(bitmap = imageData!!.asImageBitmap(), contentDescription = altText, modifier = Modifier.fillMaxWidth(), contentScale = contentScale)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color(0xFF4CAF50), modifier = Modifier.size(48.dp))
            Text(text = "Loading content...", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable

private fun ErrorState(error: String, onRetry: () -> Unit) {

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {

            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(64.dp))

            Text(text = "Error loading content", color = Color.White, style = MaterialTheme.typography.headlineSmall)

            Text(text = error, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)

            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Retry", color = Color.White) }

        }

    }

}



@Composable

private fun EmptyState(onOpenLibrary: () -> Unit) {

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {

            Icon(imageVector = Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))

            Text(text = "No content available", color = Color.White, style = MaterialTheme.typography.headlineSmall)

            Text(text = "Add a novel from the library", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)

            Button(onClick = onOpenLibrary, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Open Library", color = Color.White) }

        }

    }

}
