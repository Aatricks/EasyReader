package io.aatricks.novelscraper.ui.screens

import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.ui.ExploreRoute
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

import io.aatricks.novelscraper.ui.components.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showCloudflareWebView by remember { mutableStateOf(false) }
    var cloudflareUrl by remember { mutableStateOf("") }

    var showChapterList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()
    val settingsSheetState = rememberModalBottomSheetState()

    val uiState by readerViewModel.uiState.collectAsState()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = !drawerState.isOpen && uiState.showControls) {
        readerViewModel.hideControls()
    }

    BackHandler(enabled = !drawerState.isOpen && !uiState.showControls && uiState.content != null) {
        scope.launch { drawerState.open() }
    }

    LaunchedEffect(uiState.error) {
        if (uiState.error?.contains("403") == true || uiState.error?.contains("503") == true) {
            cloudflareUrl = uiState.content?.url ?: ""
            if (cloudflareUrl.startsWith("http")) {
                showCloudflareWebView = true
            }
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            readerViewModel.clearToast()
        }
    }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val readerTheme = uiState.readerTheme

    LaunchedEffect(uiState.showControls, readerTheme) {
        if (window != null) {
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            val isDarkReader = readerTheme == ReaderTheme.DARK || 
                               readerTheme == ReaderTheme.OLED

            if (!uiState.showControls) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.isAppearanceLightStatusBars = !isDarkReader
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.isAppearanceLightStatusBars = false 
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                LibraryDrawerContent(
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel,
                    navController = navController,
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
                    uiState.isLoading -> LoadingState()
                    uiState.error != null -> ErrorState(error = uiState.error!!, onRetry = { readerViewModel.retryLoad() })
                    uiState.content == null -> EmptyState(onOpenLibrary = { scope.launch { drawerState.open() } })
                    else -> ContentArea(
                        content = uiState.content!!,
                        readerViewModel = readerViewModel,
                        libraryViewModel = libraryViewModel,
                        onLibraryClick = { scope.launch { drawerState.open() } },
                        onShowChapterList = { showChapterList = true },
                        onShowSettings = { showSettings = true }
                    )
                }

                if (uiState.isNavigating) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f))
                            .pointerInput(Unit) {},
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4CAF50))
                    }
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            uiState = uiState,
            onDismiss = { showSettings = false },
            onUpdateFontSize = { readerViewModel.updateFontSize(it) },
            onUpdateLineHeight = { readerViewModel.updateLineHeight(it) },
            onUpdateFontFamily = { readerViewModel.updateFontFamily(it) },
            onUpdateMargins = { readerViewModel.updateMargins(it) },
            onUpdateParagraphSpacing = { readerViewModel.updateParagraphSpacing(it) },
            onUpdateReaderTheme = { readerViewModel.updateReaderTheme(it) },
            sheetState = settingsSheetState
        )
    }

    if (showChapterList) {
        ChapterListSheet(
            uiState = uiState,
            libraryViewModel = libraryViewModel,
            onDismiss = { showChapterList = false },
            onNavigateToChapter = { url, title ->
                scope.launch {
                    bottomSheetState.hide()
                    showChapterList = false
                    readerViewModel.navigateToChapter(url, title)
                }
            },
            sheetState = bottomSheetState
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentArea(
    content: ChapterContent,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onLibraryClick: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
) {
    val uiState by readerViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val fontFamily = when (uiState.fontFamily) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

    val isManhwa = remember(content) {
        content.getImageCount() > content.getTextCount() && content.getImageCount() > 2
    }

    // Keyed states to ensure fresh state and correct initial position upon novel switch
    val listState = key(content.url) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = uiState.scrollIndex,
            initialFirstVisibleItemScrollOffset = uiState.scrollOffset
        )
    }

    val pagerState = key(content.url) {
        rememberPagerState(
            initialPage = uiState.scrollIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0)),
            initialPageOffsetFraction = 0f
        ) {
            content.paragraphs.size
        }
    }

    // Prefetch images nearby
    LaunchedEffect(listState.firstVisibleItemIndex, content.url) {
        if (isManhwa) {
            val currentIndex = listState.firstVisibleItemIndex
            val prefetchRange = 5
            val endRange = (currentIndex + prefetchRange).coerceAtMost(content.paragraphs.size - 1)
            
            for (i in currentIndex..endRange) {
                val element = content.paragraphs.getOrNull(i)
                if (element is ContentElement.Image) {
                    val request = ImageRequest.Builder(context)
                        .data(element.url)
                        .build()
                    SingletonImageLoader.get(context).enqueue(request)
                } else if (element is ContentElement.ImageGroup) {
                    element.images.forEach { img ->
                        val request = ImageRequest.Builder(context)
                            .data(img.url)
                            .build()
                        SingletonImageLoader.get(context).enqueue(request)
                    }
                }
            }
        }
    }

    // Handle explicit seeks from the slider
    LaunchedEffect(uiState.seekTrigger) {
        if (content.paragraphs.isNotEmpty() && uiState.seekTrigger > 0L) {
            val targetIndex = uiState.scrollIndex
            val targetOffset = uiState.scrollOffset

            if (uiState.isPagedMode) {
                val page = targetIndex.coerceIn(0, content.paragraphs.size - 1)
                pagerState.scrollToPage(page)
            } else {
                if (targetIndex >= 0) {
                    try {
                        listState.scrollToItem(targetIndex, targetOffset)
                    } catch (_: Exception) {
                        val totalItems = content.paragraphs.size
                        val percent = uiState.scrollPosition.coerceIn(0f, 100f) / 100f
                        val index = (percent * totalItems).toInt().coerceIn(0, totalItems - 1)
                        listState.scrollToItem(index, 0)
                    }
                }
            }
        }
    }

    if (uiState.isPagedMode) {
        LaunchedEffect(pagerState.currentPage) {
            val totalItems = content.paragraphs.size
            val currentItem = pagerState.currentPage
            val progress = if (totalItems > 0) ((currentItem.toFloat() / (totalItems - 1).coerceAtLeast(1)) * 100f).coerceIn(0f, 100f) else 0f

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
                val layoutInfo = listState.layoutInfo
                val visibleItems = layoutInfo.visibleItemsInfo

                if (visibleItems.isNotEmpty()) {
                    val totalItems = layoutInfo.totalItemsCount
                    val firstItem = visibleItems.first()

                    val currentScrollOffset = firstItem.index.toFloat() +
                        (listState.firstVisibleItemScrollOffset.toFloat() / firstItem.size.coerceAtLeast(1).toFloat())

                    val maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat()
                    val viewportHeightInItems = layoutInfo.viewportSize.height.toFloat() / firstItem.size.coerceAtLeast(1).toFloat()

                    readerViewModel.updateScrollPosition(
                        scrollOffset = currentScrollOffset,
                        maxScrollOffset = maxScrollOffset + viewportHeightInItems,
                        viewportHeight = viewportHeightInItems,
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset
                    )
                }
            }
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = remember { with(density) { 80.dp.toPx() } }
    var pullAmount by remember { mutableFloatStateOf(0f) }
    val isThresholdReached = abs(pullAmount) >= threshold

    LaunchedEffect(content.url) {
        pullAmount = 0f
    }

    val nestedScrollConnection = remember(content, uiState.isPagedMode, uiState.isRtl, pagerState.currentPage) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if ((abs(available.y) > 5f || abs(available.x) > 5f) && source == NestedScrollSource.Drag) {
                    readerViewModel.hideControls()
                }

                if (uiState.isPagedMode) {
                    if (pullAmount > 0 && available.x < 0) {
                        val consumed = available.x.coerceAtLeast(-pullAmount)
                        pullAmount += consumed
                        return Offset(consumed, 0f)
                    }
                    if (pullAmount < 0 && available.x > 0) {
                        val consumed = available.x.coerceAtMost(-pullAmount)
                        pullAmount += consumed
                        return Offset(consumed, 0f)
                    }
                } else {
                    if (pullAmount > 0 && available.y < 0) {
                        val consumed = available.y.coerceAtLeast(-pullAmount)
                        pullAmount += consumed
                        return Offset(0f, consumed)
                    }
                    if (pullAmount < 0 && available.y > 0) {
                        val consumed = available.y.coerceAtMost(-pullAmount)
                        pullAmount += consumed
                        return Offset(0f, consumed)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag) {
                    if (uiState.isPagedMode) {
                        val isAtStart = pagerState.currentPage == 0
                        val isAtEnd = pagerState.currentPage == content.paragraphs.size - 1

                        if (uiState.isRtl) {
                            if (available.x < 0 && isAtStart && uiState.canNavigatePrevious) {
                                pullAmount += available.x * 0.5f
                                return Offset(available.x, 0f)
                            } else if (available.x > 0 && isAtEnd && uiState.canNavigateNext) {
                                pullAmount += available.x * 0.5f
                                return Offset(available.x, 0f)
                            }
                        } else {
                            if (available.x > 0 && isAtStart && uiState.canNavigatePrevious) {
                                pullAmount += available.x * 0.5f
                                return Offset(available.x, 0f)
                            } else if (available.x < 0 && isAtEnd && uiState.canNavigateNext) {
                                pullAmount += available.x * 0.5f
                                return Offset(available.x, 0f)
                            }
                        }
                    } else {
                        val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        val isAtBottom = !listState.canScrollForward

                        if (available.y > 0 && isAtTop && uiState.canNavigatePrevious) {
                            pullAmount += available.y * 0.5f
                            return Offset(0f, available.y)
                        } else if (available.y < 0 && isAtBottom && uiState.canNavigateNext) {
                            pullAmount += available.y * 0.5f
                            return Offset(0f, available.y)
                        }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val currentPull = pullAmount
                if (abs(currentPull) >= threshold) {
                    val isPrevious = if (uiState.isPagedMode) {
                        if (uiState.isRtl) currentPull < 0 else currentPull > 0
                    } else {
                        currentPull > 0
                    }

                    if (isPrevious) {
                        readerViewModel.navigateToPreviousChapter(fromBottom = true)
                    } else {
                        readerViewModel.navigateToNextChapter()
                    }
                }
                pullAmount = 0f
                return Velocity.Zero
            }
        }
    }

    val readerThemeState = uiState.readerTheme
    val bgColor = readerThemeState.backgroundColor
    val textColor = readerThemeState.textColor

    Box(modifier = Modifier
        .fillMaxSize()
        .nestedScroll(nestedScrollConnection)
        .background(bgColor)
    ) {
        if (uiState.isPagedMode) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = uiState.isRtl,
                userScrollEnabled = !uiState.showControls,
                modifier = Modifier
                    .fillMaxSize()
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
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = uiState.fontSize.sp,
                                            lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                            fontFamily = fontFamily
                                        ),
                                        modifier = Modifier.padding(uiState.margins.dp).fillMaxWidth()
                                    )
                                }
                            }
                            is ContentElement.Image -> {
                                ReaderImageView(
                                    imageUrl = element.url,
                                    altText = element.altText,
                                    readerViewModel = readerViewModel,
                                    pageUrl = content.url,
                                    contentScale = ContentScale.Fit,
                                    backgroundColor = bgColor,
                                    width = element.width,
                                    height = element.height
                                )
                            }
                            is ContentElement.ImageGroup -> {
                                Column(
                                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    element.images.forEach { img ->
                                        ReaderImageView(
                                            imageUrl = img.url,
                                            altText = img.altText,
                                            readerViewModel = readerViewModel,
                                            pageUrl = content.url,
                                            contentScale = ContentScale.FillWidth,
                                            backgroundColor = bgColor,
                                            width = img.width,
                                            height = img.height
                                        )
                                    }
                                }
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
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { readerViewModel.toggleControls() })
                    },
                verticalArrangement = if (isManhwa) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
            ) {
                itemsIndexed(
                    content.paragraphs,
                    key = { index: Int, _: ContentElement -> "${content.url}_$index" }) { index: Int, element: ContentElement ->
                    when (element) {
                        is ContentElement.Text -> {
                            Text(
                                text = element.content,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = uiState.fontSize.sp,
                                    lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                    fontFamily = fontFamily
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = uiState.margins.dp)
                            )
                        }
                        is ContentElement.Image -> {
                            ReaderImageView(
                                imageUrl = element.url,
                                altText = element.altText,
                                readerViewModel = readerViewModel,
                                pageUrl = content.url,
                                contentScale = ContentScale.FillWidth,
                                backgroundColor = bgColor,
                                width = element.width,
                                height = element.height
                            )
                        }
                        is ContentElement.ImageGroup -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                element.images.forEach { img ->
                                    ReaderImageView(
                                        imageUrl = img.url,
                                        altText = img.altText,
                                        readerViewModel = readerViewModel,
                                        pageUrl = content.url,
                                        contentScale = ContentScale.FillWidth,
                                        backgroundColor = bgColor,
                                        width = img.width,
                                        height = img.height
                                    )
                                }
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
                onShowChapterList = onShowChapterList,
                onShowSettings = onShowSettings
            )
        }

        AnimatedVisibility(
            visible = uiState.showControls,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomNavigationBar(
                progress = uiState.scrollPosition,
                canNavigatePrevious = uiState.canNavigatePrevious,
                canNavigateNext = uiState.canNavigateNext,
                onPreviousClick = { readerViewModel.navigateToPreviousChapter() },
                onNextClick = { readerViewModel.navigateToNextChapter() },
                onProgressChange = { readerViewModel.seekToProgress(it) }
            )
        }

        if (abs(pullAmount) > 0f) {
            val isPrevious = if (uiState.isPagedMode) {
                if (uiState.isRtl) pullAmount < 0 else pullAmount > 0
            } else {
                pullAmount > 0
            }

            val arrowColor by animateColorAsState(
                if (isThresholdReached) Color(0xFF4CAF50) else Color.White,
                label = "arrowColor"
            )

            val alignment = if (uiState.isPagedMode) {
                if (isPrevious) {
                    if (uiState.isRtl) Alignment.CenterStart else Alignment.CenterEnd
                } else {
                    if (uiState.isRtl) Alignment.CenterEnd else Alignment.CenterStart
                }
            } else {
                if (isPrevious) Alignment.TopCenter else Alignment.BottomCenter
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (abs(pullAmount) / threshold * 0.4f).coerceAtMost(0.4f))),
                contentAlignment = alignment
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    val icon = if (uiState.isPagedMode) {
                        if (isPrevious) {
                            if (uiState.isRtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward
                        } else {
                            if (uiState.isRtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack
                        }
                    } else {
                        if (isPrevious) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
                    }

                    val rotation by animateFloatAsState(if (isThresholdReached) 180f else 0f, label = "arrowRotation")

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = arrowColor,
                        modifier = Modifier
                            .size(48.dp)
                            .rotate(if (uiState.isPagedMode) 0f else rotation)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isPrevious) {
                            if (isThresholdReached) "Release for Previous Chapter" else "Pull for Previous Chapter"
                        } else {
                            if (isThresholdReached) "Release for Next Chapter" else "Pull for Next Chapter"
                        },
                        color = arrowColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}