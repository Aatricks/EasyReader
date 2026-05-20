package io.aatricks.easyreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.ui.ExploreRoute
import io.aatricks.easyreader.ui.LibraryRoute
import io.aatricks.easyreader.ui.ReaderRoute
import io.aatricks.easyreader.ui.SettingsRoute
import io.aatricks.easyreader.ui.screens.LibraryScreen
import io.aatricks.easyreader.ui.screens.ReaderScreen
import io.aatricks.easyreader.ui.screens.explore.ExploreScreen
import io.aatricks.easyreader.ui.screens.settings.SettingsScreen
import io.aatricks.easyreader.ui.theme.NovelScraperTheme
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.util.FileUtils
import io.aatricks.easyreader.util.UrlSecurity
import io.aatricks.easyreader.work.LibraryUpdateWorker
import kotlinx.coroutines.launch
import javax.inject.Inject

import androidx.hilt.navigation.compose.hiltViewModel
import io.aatricks.easyreader.ui.viewmodel.ExploreViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object {
        private const val TAG = "MainActivity"
        private val URL_REGEX = Regex("https?://[^\\s]+")
    }

    private val readerViewModel: ReaderViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    
    @Inject lateinit var contentRepository: ContentRepository

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleFilePicked(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() must run before super.onCreate so the splash window
        // is composited correctly. It also swaps the activity theme to the post-splash
        // Theme.EasyReader so Compose inherits the right windowBackground.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        LibraryUpdateWorker.schedule(applicationContext)

        setContent {
            val readerUiState by readerViewModel.uiState.collectAsState()

            NovelScraperTheme(
                darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                dynamicColor = false,
                accentTheme = readerUiState.accentTheme
            ) {
                val navController = rememberNavController()

                readerUiState.pendingExternalUrl
                    ?.takeIf { readerUiState.showExternalUrlConfirmation }
                    ?.let { externalUrl ->
                        io.aatricks.easyreader.ui.components.ExternalUrlConfirmationDialog(
                            url = externalUrl,
                            onConfirm = {
                                readerViewModel.confirmExternalUrl()
                                navController.navigate(ReaderRoute) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onCancel = { readerViewModel.cancelExternalUrl() }
                        )
                    }

                readerUiState.pendingFileConfirmationUri
                    ?.takeIf { readerUiState.showFileConfirmationDialog }
                    ?.let { fileUri ->
                        io.aatricks.easyreader.ui.components.FileConfirmationDialog(
                            fileUri = fileUri,
                            onConfirm = {
                                readerViewModel.dismissFileConfirmation()
                                handleFilePicked(Uri.parse(fileUri))
                            },
                            onCancel = { readerViewModel.dismissFileConfirmation() }
                        )
                    }

                NavHost(navController = navController, startDestination = ReaderRoute) {
                    composable<ReaderRoute> {
                        ReaderScreen(
                            readerViewModel = readerViewModel,
                            libraryViewModel = libraryViewModel,
                            navController = navController,
                            onOpenFilePicker = { checkPermissionsAndOpenFilePicker() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            libraryViewModel = libraryViewModel,
                            readerViewModel = readerViewModel,
                            navController = navController,
                            onOpenFilePicker = { checkPermissionsAndOpenFilePicker() },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable<ExploreRoute> {
                        val exploreViewModel: ExploreViewModel = hiltViewModel()
                        ExploreScreen(
                            exploreViewModel = exploreViewModel,
                            libraryViewModel = libraryViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onReadItem = { item ->
                                val chapterUrl = item.readingUrl ?: item.chapters.firstOrNull()?.url ?: item.url
                                readerViewModel.loadContent(chapterUrl)
                                navController.popBackStack(ReaderRoute, inclusive = false)
                            }
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            readerViewModel = readerViewModel,
                            libraryViewModel = libraryViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Unit {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    when (uri.scheme) {
                        "http", "https" -> handleWebUrl(uri.toString())
                        "file" -> readerViewModel.requestOpenFile(uri.toString())
                        "content" -> handleFilePicked(uri)
                        else -> {
                            android.util.Log.w(TAG, "Rejected VIEW intent with scheme=${uri.scheme}")
                            Toast.makeText(this, "Unsupported link", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        URL_REGEX.find(sharedText)?.value?.let { handleWebUrl(it) }
                    }
                }
            }
        }
    }

    private fun handleWebUrl(url: String): Unit {
        if (!UrlSecurity.isSafeUrlSynchronous(url)) {
            android.util.Log.w(TAG, "Rejected unsafe URL intent")
            Toast.makeText(this, "Link blocked for safety", Toast.LENGTH_SHORT).show()
            return
        }
        readerViewModel.requestOpenUrl(url)
    }

    private fun handleFilePicked(uri: Uri): Unit {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        
        val fileType = FileUtils.detectFileType(this, uri)
        val contentType = mapFileTypeToContentType(fileType) ?: run {
            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = FileUtils.getFileName(this, uri) ?: "Unknown"
        val title = fileName.substringBeforeLast('.')
        libraryViewModel.addItem(title = title, url = uri.toString(), contentType = contentType)

        if (contentType == ContentType.EPUB) {
            loadEpubChapter(uri)
        } else {
            readerViewModel.loadContent(uri.toString())
        }
    }

    private fun mapFileTypeToContentType(fileType: FileUtils.FileType): ContentType? = when (fileType) {
        FileUtils.FileType.PDF -> ContentType.PDF
        FileUtils.FileType.HTML -> ContentType.HTML
        FileUtils.FileType.EPUB -> ContentType.EPUB
        else -> null
    }

    private fun loadEpubChapter(uri: Uri): Unit {
        lifecycleScope.launch {
            runCatching {
                val epubBook = contentRepository.getEpubBook(uri.toString())
                val firstHref = epubBook?.getFirstReadableHref()
                if (firstHref != null) {
                    readerViewModel.loadEpubChapter(uri.toString(), firstHref, null)
                } else {
                    readerViewModel.loadContent(uri.toString())
                }
            }.onFailure {
                readerViewModel.loadContent(uri.toString())
            }
        }
    }

    private fun checkPermissionsAndOpenFilePicker(): Unit {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> openFilePicker()
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> openFilePicker()
            else -> storagePermissionLauncher.launch(permission)
        }
    }

    private fun openFilePicker(): Unit {
        val mimeTypes = arrayOf("text/html", "application/xhtml+xml", "application/pdf", "application/epub+zip")
        filePickerLauncher.launch(mimeTypes)
    }

    override fun onPause(): Unit {
        super.onPause()
        runCatching {
            lifecycleScope.launch {
                readerViewModel.persistLifecycleProgress()
            }
        }
        runCatching { libraryViewModel.flushPendingDeletion() }
    }
}
