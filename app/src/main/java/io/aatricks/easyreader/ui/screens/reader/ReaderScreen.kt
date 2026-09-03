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
import androidx.hilt.navigation.compose.hiltViewModel
import io.aatricks.easyreader.R
import io.aatricks.easyreader.ui.ScrollRoute
import io.aatricks.easyreader.ui.viewmodel.ScrollViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
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
import io.aatricks.easyreader.ui.viewmodel.OpenNextChapterState
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

private const val MIN_READER_BRIGHTNESS = 0.1f
private const val MAX_READER_BRIGHTNESS = 1.0f
/** Luminance below which a background counts as dark, for picking readable foregrounds. */
internal const val DARK_SURFACE_LUMINANCE = 0.5f

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
    val snackbarHostState = remember { SnackbarHostState() }

    var showCloudflareWebView by rememberSaveable { mutableStateOf(false) }
    var cloudflareUrl by rememberSaveable { mutableStateOf("") }

    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()
    val settingsSheetState = rememberModalBottomSheetState()

    val uiState by readerViewModel.uiState.collectAsState()
    // The library view model is created lazily by the drawer and chapter sheet; the reader only
    // observes their follow-up state (next-chapter progress, download prompts) once one exists,
    // so a plain reading session never spins it up.
    var libraryViewModelInUse by remember { mutableStateOf<LibraryViewModel?>(null) }

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
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            readerViewModel.clearToast()
        }
    }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val readerTheme = uiState.readerTheme
    // Derived from the theme actually applied rather than isSystemInDarkTheme(), so forcing
    // Dark or Light in Settings on a phone set the other way still gets matching bar icons.
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < DARK_SURFACE_LUMINANCE
    val currentAppIsDark by rememberUpdatedState(appIsDark)

    val isReadingContent = uiState.content != null
    DisposableEffect(view, isReadingContent) {
        view.keepScreenOn = isReadingContent
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(uiState.showControls, readerTheme, uiState.content, appIsDark) {
        if (window != null) {
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            val isReading = uiState.content != null
            // The control bars stack a ~70% black gradient behind the status and navigation
            // bars, so while they are up the icons need to be light whatever the reader theme.
            val isDark = when {
                isReading && uiState.showControls -> true
                isReading -> readerTheme == ReaderTheme.DARK || readerTheme == ReaderTheme.OLED
                else -> appIsDark
            }
            val systemBars = WindowInsetsCompat.Type.systemBars()

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
        // Native swipe follows the finger and detects a horizontal drag anywhere on screen, so it
        // never needs the left edge -- that keeps it clear of the system back gesture. Committing
        // requires dragging past half the drawer width or a flick, so a vertical scroll won't open
        // it. Disabled in paged mode, where the left edge belongs to horizontal page-turns.
        gesturesEnabled = drawerState.isOpen || !uiState.isPagedMode,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                val drawerIsOpeningOrOpen =
                    drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open
                if (drawerIsOpeningOrOpen) {
                    val libraryViewModel = libraryViewModelProvider()
                    LaunchedEffect(libraryViewModel) { libraryViewModelInUse = libraryViewModel }
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
                        onScrollClick = {
                            navController.navigate(ScrollRoute) {
                                launchSingleTop = true
                            }
                        },
                        onOpenLibraryItem = { item ->
                            val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                            readerViewModel.loadContent(loadUrl, item.id)
                            libraryViewModel.markAsCurrentlyReading(item.id)
                        },
                        onOpenLatestUpdate = { item ->
                            if (item.baseNovelUrl.isBlank() || item.sourceName.isBlank()) {
                                val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                                readerViewModel.loadContent(loadUrl, item.id)
                                libraryViewModel.markAsCurrentlyReading(item.id)
                            } else {
                                libraryViewModel.openNewChapter(item) { url, id ->
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
                libraryViewModelInUse?.let { libraryViewModel ->
                    LibraryFollowUps(libraryViewModel, snackbarHostState)
                }

                val scrollViewModel: ScrollViewModel = hiltViewModel()
                val xpNotice by scrollViewModel.xpNotice.collectAsState()
                AnimatedVisibility(
                    visible = xpNotice != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 32.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = uiState.readerTheme.textColor.copy(alpha = 0.12f),
                        contentColor = uiState.readerTheme.textColor
                    ) {
                        Text(
                            text = xpNotice ?: "",
                            modifier = Modifier.padding(
                                horizontal = EasyReaderSpacing.sm,
                                vertical = EasyReaderSpacing.xxs
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
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
            onUpdateVerticalMargins = { readerViewModel.updateVerticalMargins(it) },
            onUpdateParagraphSpacing = { readerViewModel.updateParagraphSpacing(it) },
            onUpdateBrightness = { readerViewModel.updateBrightness(it) },
            onUpdateReaderTheme = { readerViewModel.updateReaderTheme(it) },
            sheetState = settingsSheetState
        )
    }

    if (showChapterList) {
        val libraryViewModel = libraryViewModelProvider()
        LaunchedEffect(libraryViewModel) { libraryViewModelInUse = libraryViewModel }
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

internal fun brightnessOverlayAlpha(brightness: Float): Float =
    MAX_READER_BRIGHTNESS - brightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)

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
                        .padding(EasyReaderSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.reader_cloudflare_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(R.string.reader_cloudflare_body),
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

                webViewError?.let { error ->
                    Text(
                        text = stringResource(R.string.reader_webview_error, error),
                        modifier = Modifier.padding(horizontal = EasyReaderSpacing.md),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // WebView Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = EasyReaderSpacing.md)
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
                            modifier = Modifier.align(Alignment.BottomEnd).padding(EasyReaderSpacing.md)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reader_reload))
                        }
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(EasyReaderSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
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
                        Spacer(Modifier.width(EasyReaderSpacing.xxs))
                        Text(stringResource(R.string.reader_open_in_browser))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }

                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.common_done))
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
            onRetry = { readerViewModel.retryLoad() },
            onOpenLibrary = onOpenLibraryScreen
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

/**
 * Reader-side surface for library actions started from the drawer or chapter sheet: the
 * "Read next" fetch (spinner + error) and the "Download removed / Re-download" prompt.
 * Whichever screen is on top consumes these, so nothing goes stale until the Library opens.
 */
@Composable
private fun LibraryFollowUps(libraryViewModel: LibraryViewModel, snackbarHostState: SnackbarHostState) {
    val openNextChapterState by libraryViewModel.openNextChapterState.collectAsState()
    val downloadRetryPrompt by libraryViewModel.downloadRetryPrompt.collectAsState()
    val libraryUiState by libraryViewModel.uiState.collectAsState()

    // Add, import and download failures started from the drawer or the chapter sheet used to
    // wait, unconsumed, until the Library screen next composed and then fired out of context.
    LaunchedEffect(libraryUiState.error) {
        libraryUiState.error?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            libraryViewModel.consumeError()
        }
    }
    LaunchedEffect(libraryUiState.snackbarMessage) {
        libraryUiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            libraryViewModel.consumeSnackbarMessage()
        }
    }
    if (openNextChapterState is OpenNextChapterState.Loading) {
        NavigationOverlay(onCancel = { libraryViewModel.cancelOpenNewChapter() })
    }
    LaunchedEffect(openNextChapterState) {
        (openNextChapterState as? OpenNextChapterState.Error)?.let { state ->
            snackbarHostState.showSnackbar(state.message, duration = SnackbarDuration.Short)
            libraryViewModel.consumeOpenNextChapterError()
        }
    }
    LaunchedEffect(downloadRetryPrompt) {
        downloadRetryPrompt?.let { prompt ->
            val result = snackbarHostState.showSnackbar(
                message = prompt.message,
                actionLabel = prompt.actionLabel,
                duration = SnackbarDuration.Short,
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                libraryViewModel.retryDownloads(prompt.urls)
            }
            libraryViewModel.consumeDownloadRetryPrompt()
        }
    }
}

@Composable
private fun NavigationOverlay(onCancel: (() -> Unit)? = null): Unit {
    // The overlay swallows every touch, so back has to be handled here or there is no way out.
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            CircularProgressIndicator(color = Color(0xFF4CAF50))
            if (onCancel != null) {
                Text(
                    text = stringResource(R.string.reader_opening_next_chapter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel), color = Color.White)
                }
            }
        }
    }
}
