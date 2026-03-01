package io.aatricks.novelscraper.ui.screens

import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.ui.components.*
import io.aatricks.novelscraper.util.WebViewUtils
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
): Unit {
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
            cloudflareUrl = uiState.lastAttemptedUrl ?: uiState.content?.url ?: ""
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
        CloudflareDialog(
            url = cloudflareUrl,
            onDismiss = { showCloudflareWebView = false },
            onRetry = {
                showCloudflareWebView = false
                readerViewModel.retryLoad()
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
                ReaderContent(
                    uiState = uiState,
                    readerViewModel = readerViewModel,
                    onOpenLibrary = { scope.launch { drawerState.open() } },
                    onShowChapterList = { showChapterList = true },
                    onShowSettings = { showSettings = true }
                )

                if (uiState.isNavigating) {
                    NavigationOverlay()
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

@Composable
private fun CloudflareDialog(
    url: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
): Unit {
    val context = LocalContext.current
    var webViewError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Network Access Required",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Solve the challenge or login below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (webViewError != null) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (webViewError != null) {
                    Text(
                        text = "Error: $webViewError",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // WebView Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .background(Color.White)
                ) {
                    var internalWebView by remember { mutableStateOf<WebView?>(null) }

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                internalWebView = this
                                WebViewUtils.configureCloudflareWebView(this)
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            webViewError = error?.description?.toString()
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Floating Reload Button
                    if (webViewError != null) {
                        FilledIconButton(
                            onClick = {
                                webViewError = null
                                internalWebView?.reload()
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        }
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open in Browser")
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(
    uiState: ReaderViewModel.ReaderUiState,
    readerViewModel: ReaderViewModel,
    onOpenLibrary: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
): Unit {
    when {
        uiState.isLoading -> LoadingState()
        uiState.error != null -> ErrorState(
            error = uiState.error,
            onRetry = { readerViewModel.retryLoad() }
        )
        uiState.content == null -> EmptyState(onOpenLibrary = onOpenLibrary)
        else -> ContentArea(
            content = uiState.content,
            readerViewModel = readerViewModel,
            onLibraryClick = onOpenLibrary,
            onShowChapterList = onShowChapterList,
            onShowSettings = onShowSettings
        )
    }
}

@Composable
private fun NavigationOverlay(): Unit {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentArea(
    content: ChapterContent,
    readerViewModel: ReaderViewModel,
    onLibraryClick: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
): Unit {
    val uiState by readerViewModel.uiState.collectAsState()
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

    val requestedIndices = remember(content.url) { mutableSetOf<Int>() }

    LaunchedEffect(listState.firstVisibleItemIndex, pagerState.currentPage, content.url) {
        val currentIndex = if (uiState.isPagedMode) pagerState.currentPage else listState.firstVisibleItemIndex
        prefetchImages(currentIndex, content, requestedIndices) { url ->
            val request = ImageRequest.Builder(context).data(url).build()
            SingletonImageLoader.get(context).enqueue(request)
        }
    }

    LaunchedEffect(uiState.seekTrigger) {
        if (content.paragraphs.isNotEmpty() && uiState.seekTrigger > 0L) {
            val targetIndex = uiState.scrollIndex
            val targetOffset = uiState.scrollOffset

            if (uiState.isPagedMode) {
                val page = targetIndex.coerceIn(0, content.paragraphs.size - 1)
                pagerState.scrollToPage(page)
            } else {
                if (targetIndex >= 0) {
                    runCatching {
                        listState.scrollToItem(targetIndex, targetOffset)
                    }.onFailure {
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

            readerViewModel.updateScrollPosition(
                scrollOffset = currentItem.toFloat(),
                maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat(),
                viewportHeight = 0f,
                index = currentItem,
                offset = 0
            )
        }
    } else {
        LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) {
            if (content.paragraphs.isNotEmpty()) {
                val layoutInfo = listState.layoutInfo
                val visibleItems = layoutInfo.visibleItemsInfo

                if (visibleItems.isNotEmpty()) {
                    val totalItems = layoutInfo.totalItemsCount
                    val firstItem = visibleItems.first()

                    val currentScrollOffset = firstItem.index.toFloat() +
                        (listState.firstVisibleItemScrollOffset.toFloat() / firstItem.size.coerceAtLeast(1).toFloat())

                    val viewportHeightInItems = layoutInfo.viewportSize.height.toFloat() / firstItem.size.coerceAtLeast(1).toFloat()
                    val maxScrollOffset = (totalItems - 1).coerceAtLeast(0).toFloat()

                    readerViewModel.updateScrollPosition(
                        scrollOffset = currentScrollOffset,
                        maxScrollOffset = maxScrollOffset + viewportHeightInItems,
                        viewportHeight = viewportHeightInItems,
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                        canScrollForward = listState.canScrollForward
                    )
                }
            }
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = remember { with(density) { 80.dp.toPx() } }
    var pullAmount by remember { mutableFloatStateOf(0f) }
    val isThresholdReached = abs(pullAmount) >= threshold

    val nestedScrollConnection = rememberReaderNestedScrollConnection(
        uiState = uiState,
        pagerState = pagerState,
        listState = listState,
        content = content,
        threshold = threshold,
        onHideControls = { readerViewModel.hideControls() },
        onUserInteraction = { readerViewModel.onUserInteraction() },
        onPullAmountChange = { pullAmount = it },
        onNavigatePrevious = { readerViewModel.navigateToPreviousChapter(fromBottom = true) },
        onNavigateNext = { readerViewModel.navigateToNextChapter() }
    )

    val readerThemeState = uiState.readerTheme
    val bgColor = readerThemeState.backgroundColor
    val textColor = readerThemeState.textColor

    Box(modifier = Modifier
        .fillMaxSize()
        .nestedScroll(nestedScrollConnection)
        .background(bgColor)
    ) {
        if (uiState.isPagedMode) {
            PagedReaderView(
                content = content,
                pagerState = pagerState,
                uiState = uiState,
                fontFamily = fontFamily,
                bgColor = bgColor,
                textColor = textColor,
                readerViewModel = readerViewModel,
                isZoomable = isManhwa // True if image content
            )
        } else {
            ScrollingReaderView(
                content = content,
                listState = listState,
                uiState = uiState,
                isManhwa = isManhwa,
                fontFamily = fontFamily,
                bgColor = bgColor,
                textColor = textColor,
                readerViewModel = readerViewModel
            )
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
                onPreviousClick = { readerViewModel.navigateToPreviousChapter(fromBottom = true) },
                onNextClick = { readerViewModel.navigateToNextChapter() },
                onProgressChange = { readerViewModel.seekToProgress(it) }
            )
        }

        PullToNavigateOverlay(
            pullAmount = pullAmount,
            threshold = threshold,
            isThresholdReached = isThresholdReached,
            isPagedMode = uiState.isPagedMode,
            isRtl = uiState.isRtl
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReaderView(
    content: ChapterContent,
    pagerState: PagerState,
    uiState: ReaderViewModel.ReaderUiState,
    fontFamily: FontFamily,
    bgColor: Color,
    textColor: Color,
    readerViewModel: ReaderViewModel,
    isZoomable: Boolean
): Unit {
    HorizontalPager(
        state = pagerState,
        reverseLayout = uiState.isRtl,
        userScrollEnabled = !uiState.showControls,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val element = content.paragraphs.getOrNull(page)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            element?.let { el ->
                when (el) {
                    is ContentElement.Placeholder -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { readerViewModel.toggleControls() }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = el.text,
                                color = textColor.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = uiState.fontSize.sp,
                                    fontFamily = fontFamily
                                )
                            )
                        }
                    }
                    is ContentElement.PageContent -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { readerViewModel.toggleControls() }
                                )
                                .padding(uiState.margins.dp),
                            verticalArrangement = Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
                        ) {
                            el.elements.forEach { subElement ->
                                when (subElement) {
                                    is ContentElement.Text -> {
                                        Text(
                                            text = subElement.content,
                                            color = textColor,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = uiState.fontSize.sp,
                                                lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                                fontFamily = fontFamily
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    is ContentElement.Image -> {
                                        ReaderImageView(
                                            imageUrl = subElement.url,
                                            altText = subElement.altText,
                                            readerViewModel = readerViewModel,
                                            pageUrl = content.url,
                                            contentScale = ContentScale.Fit,
                                            backgroundColor = bgColor,
                                            width = subElement.width,
                                            height = subElement.height,
                                            side = subElement.side,
                                            enableZoom = isZoomable,
                                            onTap = { readerViewModel.toggleControls() }
                                        )
                                    }
                                    else -> {} // Should not be nested
                                }
                            }
                        }
                    }
                    is ContentElement.Text -> {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            )
                        ) {
                            Text(
                                text = el.content,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = uiState.fontSize.sp,
                                    lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                    fontFamily = fontFamily
                                ),
                                modifier = Modifier
                                    .padding(uiState.margins.dp)
                                    .fillMaxWidth()
                            )
                        }
                    }
                    is ContentElement.Image -> {
                        ReaderImageView(
                            imageUrl = el.url,
                            altText = el.altText,
                            readerViewModel = readerViewModel,
                            pageUrl = content.url,
                            contentScale = ContentScale.Fit,
                            backgroundColor = bgColor,
                            width = el.width,
                            height = el.height,
                            side = el.side,
                            enableZoom = isZoomable,
                            onTap = { readerViewModel.toggleControls() }
                        )
                    }
                    is ContentElement.ImageGroup -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            el.images.forEach { img ->
                                ReaderImageView(
                                    imageUrl = img.url,
                                    altText = img.altText,
                                    readerViewModel = readerViewModel,
                                    pageUrl = content.url,
                                    contentScale = ContentScale.FillWidth,
                                    backgroundColor = bgColor,
                                    width = img.width,
                                    height = img.height,
                                    side = img.side,
                                    enableZoom = isZoomable,
                                    onTap = { readerViewModel.toggleControls() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollingReaderView(
    content: ChapterContent,
    listState: LazyListState,
    uiState: ReaderViewModel.ReaderUiState,
    isManhwa: Boolean,
    fontFamily: FontFamily,
    bgColor: Color,
    textColor: Color,
    readerViewModel: ReaderViewModel
): Unit {
    LaunchedEffect(uiState.targetScrollPosition, listState.canScrollForward) {
        if (uiState.targetScrollPosition == 100f && content.paragraphs.isNotEmpty()) {
            if (listState.canScrollForward) {
                listState.scrollToItem(content.paragraphs.size - 1, 10000000)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = if (isManhwa) {
            Arrangement.spacedBy(0.dp)
        } else {
            Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
        }
    ) {
        itemsIndexed(
            content.paragraphs,
            key = { index, _ -> "${content.url}_$index" }
        ) { _, element ->
            when (element) {
                is ContentElement.Placeholder -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(element.heightDp.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = element.text,
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = uiState.fontSize.sp,
                                fontFamily = fontFamily
                            )
                        )
                    }
                }
                is ContentElement.PageContent -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            )
                            .padding(horizontal = uiState.margins.dp),
                        verticalArrangement = Arrangement.spacedBy((uiState.fontSize * uiState.paragraphSpacing).dp)
                    ) {
                        element.elements.forEach { subElement ->
                            when (subElement) {
                                is ContentElement.Text -> {
                                    Text(
                                        text = subElement.content,
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = uiState.fontSize.sp,
                                            lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                                            fontFamily = fontFamily
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                is ContentElement.Image -> {
                                    ReaderImageView(
                                        imageUrl = subElement.url,
                                        altText = subElement.altText,
                                        readerViewModel = readerViewModel,
                                        pageUrl = content.url,
                                        contentScale = ContentScale.FillWidth,
                                        backgroundColor = bgColor,
                                        width = subElement.width,
                                        height = subElement.height,
                                        side = subElement.side,
                                        enableZoom = false,
                                        dynamicHeight = false,
                                        onTap = { readerViewModel.toggleControls() }
                                    )
                                }
                                else -> {} // Should not be nested
                            }
                        }
                    }
                }
                is ContentElement.Text -> {
                    Text(
                        text = element.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = uiState.fontSize.sp,
                            lineHeight = (uiState.fontSize * uiState.lineHeight).sp,
                            fontFamily = fontFamily
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = uiState.margins.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { readerViewModel.toggleControls() }
                            )
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
                        height = element.height,
                        side = element.side,
                        enableZoom = false,
                        dynamicHeight = false,
                        onTap = { readerViewModel.toggleControls() }
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
                                height = img.height,
                                side = img.side,
                                enableZoom = false,
                                dynamicHeight = false,
                                onTap = { readerViewModel.toggleControls() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PullToNavigateOverlay(
    pullAmount: Float,
    threshold: Float,
    isThresholdReached: Boolean,
    isPagedMode: Boolean,
    isRtl: Boolean
): Unit {
    if (abs(pullAmount) <= 0f) return

    val isPrevious = if (isPagedMode) {
        if (isRtl) pullAmount < 0 else pullAmount > 0
    } else {
        pullAmount > 0
    }

    val arrowColor by animateColorAsState(
        if (isThresholdReached) Color(0xFF4CAF50) else Color.White,
        label = "arrowColor"
    )

    val alignment = when {
        isPagedMode && isPrevious -> if (isRtl) Alignment.CenterStart else Alignment.CenterEnd
        isPagedMode && !isPrevious -> if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
        !isPagedMode && isPrevious -> Alignment.TopCenter
        else -> Alignment.BottomCenter
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = (abs(pullAmount) / threshold * 0.4f).coerceAtMost(0.4f)
                )
            ),
        contentAlignment = alignment
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            val icon = when {
                isPagedMode && isPrevious -> if (isRtl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward
                isPagedMode && !isPrevious -> if (isRtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack
                !isPagedMode && isPrevious -> Icons.Default.ArrowDownward
                else -> Icons.Default.ArrowUpward
            }

            val rotation by animateFloatAsState(
                if (isThresholdReached) 180f else 0f,
                label = "arrowRotation"
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = arrowColor,
                modifier = Modifier
                    .size(48.dp)
                    .rotate(if (isPagedMode) 0f else rotation)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isPrevious && isThresholdReached -> "Release for Previous Chapter"
                    isPrevious && !isThresholdReached -> "Pull for Previous Chapter"
                    !isPrevious && isThresholdReached -> "Release for Next Chapter"
                    else -> "Pull for Next Chapter"
                },
                color = arrowColor,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberReaderNestedScrollConnection(
    uiState: ReaderViewModel.ReaderUiState,
    pagerState: PagerState,
    listState: LazyListState,
    content: ChapterContent,
    threshold: Float,
    onHideControls: () -> Unit,
    onUserInteraction: () -> Unit,
    onPullAmountChange: (Float) -> Unit,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit
): NestedScrollConnection {
    var pullAmount by remember { mutableFloatStateOf(0f) }

    return remember(content, uiState.isPagedMode, uiState.isRtl, pagerState.currentPage) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (abs(available.y) > 5f || abs(available.x) > 5f) {
                        onHideControls()
                    }
                    onUserInteraction()
                }

                if (uiState.isPagedMode) {
                    if (pullAmount > 0 && available.x < 0) {
                        val consumed = available.x.coerceAtLeast(-pullAmount)
                        pullAmount += consumed
                        onPullAmountChange(pullAmount)
                        return Offset(consumed, 0f)
                    }
                    if (pullAmount < 0 && available.x > 0) {
                        val consumed = available.x.coerceAtMost(-pullAmount)
                        pullAmount += consumed
                        onPullAmountChange(pullAmount)
                        return Offset(consumed, 0f)
                    }
                } else {
                    if (pullAmount > 0 && available.y < 0) {
                        val consumed = available.y.coerceAtLeast(-pullAmount)
                        pullAmount += consumed
                        onPullAmountChange(pullAmount)
                        return Offset(0f, consumed)
                    }
                    if (pullAmount < 0 && available.y > 0) {
                        val consumed = available.y.coerceAtMost(-pullAmount)
                        pullAmount += consumed
                        onPullAmountChange(pullAmount)
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
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                if (uiState.isPagedMode) {
                    val isAtStart = pagerState.currentPage == 0
                    val isAtEnd = pagerState.currentPage == content.paragraphs.size - 1

                    if (uiState.isRtl) {
                        if (available.x < 0 && isAtStart && uiState.canNavigatePrevious) {
                            pullAmount += available.x * 0.5f
                            onPullAmountChange(pullAmount)
                            return Offset(available.x, 0f)
                        } else if (available.x > 0 && isAtEnd && uiState.canNavigateNext) {
                            pullAmount += available.x * 0.5f
                            onPullAmountChange(pullAmount)
                            return Offset(available.x, 0f)
                        }
                    } else {
                        if (available.x > 0 && isAtStart && uiState.canNavigatePrevious) {
                            pullAmount += available.x * 0.5f
                            onPullAmountChange(pullAmount)
                            return Offset(available.x, 0f)
                        } else if (available.x < 0 && isAtEnd && uiState.canNavigateNext) {
                            pullAmount += available.x * 0.5f
                            onPullAmountChange(pullAmount)
                            return Offset(available.x, 0f)
                        }
                    }
                } else {
                    val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    val isAtBottom = !listState.canScrollForward

                    if (available.y > 0 && isAtTop && uiState.canNavigatePrevious) {
                        pullAmount += available.y * 0.5f
                        onPullAmountChange(pullAmount)
                        return Offset(0f, available.y)
                    } else if (available.y < 0 && isAtBottom && uiState.canNavigateNext) {
                        pullAmount += available.y * 0.5f
                        onPullAmountChange(pullAmount)
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(pullAmount) >= threshold) {
                    val isPrevious = if (uiState.isPagedMode) {
                        if (uiState.isRtl) pullAmount < 0 else pullAmount > 0
                    } else {
                        pullAmount > 0
                    }

                    if (isPrevious) {
                        onNavigatePrevious()
                    } else {
                        onNavigateNext()
                    }
                }
                pullAmount = 0f
                onPullAmountChange(0f)
                return Velocity.Zero
            }
        }
    }
}

internal fun prefetchImages(
    currentIndex: Int,
    content: ChapterContent,
    requestedIndices: MutableSet<Int>,
    onEnqueue: (String) -> Unit
) {
    val prefetchRange = 10

    val startRange = (currentIndex - 3).coerceAtLeast(0)
    val endRange = (currentIndex + prefetchRange).coerceAtMost(content.paragraphs.size - 1)

    for (i in startRange..endRange) {
        if (i in requestedIndices) continue

        content.paragraphs.getOrNull(i)?.let { element ->
            when (element) {
                is ContentElement.Image -> {
                    onEnqueue(element.url)
                    requestedIndices.add(i)
                }
                is ContentElement.ImageGroup -> {
                    element.images.forEach { img ->
                        onEnqueue(img.url)
                    }
                    requestedIndices.add(i)
                }
                else -> {
                    requestedIndices.add(i)
                }
            }
        }
    }
}
