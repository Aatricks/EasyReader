package io.aatricks.easyreader.ui.screens

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.ui.components.*
import io.aatricks.easyreader.ui.LibraryRoute
import io.aatricks.easyreader.util.WebViewUtils
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModelProvider: () -> LibraryViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier
): Unit {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showCloudflareWebView by rememberSaveable { mutableStateOf(false) }
    var cloudflareUrl by rememberSaveable { mutableStateOf("") }

    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()
    val settingsSheetState = rememberModalBottomSheetState()

    val uiState by readerViewModel.uiState.collectAsState()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = !drawerState.isOpen && uiState.showControls) {
        readerViewModel.hideControls()
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
    val appIsDark = isSystemInDarkTheme()
    val currentAppIsDark by rememberUpdatedState(appIsDark)

    LaunchedEffect(uiState.showControls, readerTheme, uiState.content, appIsDark) {
        if (window != null) {
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            val isDark = if (uiState.content != null) {
                readerTheme == ReaderTheme.DARK || readerTheme == ReaderTheme.OLED
            } else {
                appIsDark
            }
            val systemBars = WindowInsetsCompat.Type.systemBars()
            val isReading = uiState.content != null

            if (isReading && !uiState.showControls) {
                windowInsetsController.hide(systemBars)
                windowInsetsController.isAppearanceLightStatusBars = !isDark
                windowInsetsController.isAppearanceLightNavigationBars = !isDark
            } else {
                windowInsetsController.show(systemBars)
                windowInsetsController.isAppearanceLightStatusBars = !isDark
                windowInsetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            window?.let {
                val windowInsetsController = WindowCompat.getInsetsController(it, view)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.isAppearanceLightStatusBars = !currentAppIsDark
                windowInsetsController.isAppearanceLightNavigationBars = !currentAppIsDark
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
        // Edge-swipe opens the drawer in continuous (manhwa/webtoon) scrolling, but only while the
        // controls are visible — during immersive reading a mostly-vertical drag near the left edge
        // was opening it by accident. In paged mode the left edge belongs to horizontal page-turns,
        // so only allow the swipe once the drawer is already open (to close it). It's always
        // reachable via the toolbar library button and the back button.
        gesturesEnabled = drawerState.isOpen || (!uiState.isPagedMode && uiState.showControls),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                val drawerIsOpeningOrOpen =
                    drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open
                if (drawerIsOpeningOrOpen) {
                    val libraryViewModel = libraryViewModelProvider()
                    val drawerUi by libraryViewModel.drawerUiState.collectAsState()
                    LibraryDrawerContent(
                        drawerSections = drawerUi.sections,
                        isLibraryEmpty = drawerUi.isLibraryEmpty,
                        onOpenFilePicker = onOpenFilePicker,
                        onCloseDrawer = {
                            scope.launch { drawerState.close() }
                        },
                        onLibraryClick = {
                            navController.navigate(LibraryRoute) {
                                launchSingleTop = true
                            }
                        },
                        onDiscoverClick = {
                            navController.navigate(io.aatricks.easyreader.ui.ExploreRoute) {
                                launchSingleTop = true
                            }
                        },
                        onOpenLibraryItem = { item ->
                            val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                            readerViewModel.loadContent(loadUrl, item.id)
                            libraryViewModel.markAsCurrentlyReading(item.id)
                        },
                        onOpenLatestUpdate = { item ->
                            val baseTitle = item.baseTitle.ifBlank { item.title }
                            if (item.baseNovelUrl.isBlank() || item.sourceName.isBlank()) {
                                val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                                readerViewModel.openChapterFromStart(loadUrl, item.id)
                                libraryViewModel.markAsCurrentlyReading(item.id)
                            } else {
                                libraryViewModel.openNewChapter(baseTitle, item.baseNovelUrl, item.sourceName) { url, id ->
                                    readerViewModel.openChapterFromStart(url, id)
                                    libraryViewModel.markAsCurrentlyReading(id)
                                }
                            }
                        }
                    )
                }
            }
        }
    ) {
        @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(
            containerColor = if (uiState.content != null) Color.Black else MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) {
            // Reader content is always edge-to-edge -- it must not depend on Scaffold's
            // (system-bar) inset padding, which Android keeps reporting as non-zero while
            // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE is active even after hide(), so consuming
            // it here left a black gap where the status bar used to be. TopInfoBar and
            // BottomNavigationBar apply their own statusBars/navigationBars padding instead,
            // since they only render while the bars are actually shown.
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                ReaderContent(
                    uiState = uiState,
                    readerViewModel = readerViewModel,
                    onOpenLibrary = { scope.launch { drawerState.open() } },
                    onOpenLibraryScreen = {
                        navController.navigate(LibraryRoute) {
                            launchSingleTop = true
                        }
                    },
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
            onUpdatePagedMode = { readerViewModel.setPagedMode(it) },
            onUpdateRtl = { readerViewModel.setRtl(it) },
            onUpdateFontSize = { readerViewModel.updateFontSize(it) },
            onUpdateLineHeight = { readerViewModel.updateLineHeight(it) },
            onUpdateFontFamily = { readerViewModel.updateFontFamily(it) },
            onUpdateMargins = { readerViewModel.updateMargins(it) },
            onUpdateParagraphSpacing = { readerViewModel.updateParagraphSpacing(it) },
            onUpdateReaderTheme = { readerViewModel.updateReaderTheme(it) },
            onUpdateAccentTheme = { readerViewModel.updateAccentTheme(it) },
            sheetState = settingsSheetState
        )
    }

    if (showChapterList) {
        ChapterListSheet(
            uiState = uiState,
            libraryViewModel = libraryViewModelProvider(),
            onDismiss = { showChapterList = false },
            onNavigateToChapter = { url, title ->
                scope.launch {
                    bottomSheetState.hide()
                    showChapterList = false
                    readerViewModel.navigateToChapter(url, title)
                }
            },
            onDownloadRemoved = {
                scope.launch {
                    snackbarHostState.showSnackbar("Chapter download removed")
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

                    DisposableEffect(Unit) {
                        onDispose {
                            internalWebView?.apply {
                                stopLoading()
                                loadUrl("about:blank")
                                clearHistory()
                                removeAllViews()
                                destroy()
                            }
                            internalWebView = null
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                internalWebView = this
                                WebViewUtils.configureCloudflareWebView(this)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        val navUrl = request?.url?.toString()
                                        val expectedHost = url.toHttpUrlOrNull()?.host
                                        return !WebViewUtils.shouldAllowCloudflareNavigation(navUrl, expectedHost)
                                    }

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
    onOpenLibraryScreen: () -> Unit,
    onShowChapterList: () -> Unit,
    onShowSettings: () -> Unit
): Unit {
    when {
        uiState.isLoading -> LoadingState()
        uiState.error != null -> ErrorState(
            error = uiState.error,
            onRetry = { readerViewModel.retryLoad() }
        )
        uiState.content == null -> EmptyState(onOpenLibrary = onOpenLibraryScreen)
        else -> ContentArea(
            uiState = uiState,
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
