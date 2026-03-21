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
import io.aatricks.novelscraper.ui.LibraryRoute
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

