package io.aatricks.novelscraper

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
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.ui.ExploreRoute
import io.aatricks.novelscraper.ui.ReaderRoute
import io.aatricks.novelscraper.ui.screens.ReaderScreen
import io.aatricks.novelscraper.ui.screens.explore.ExploreScreen
import io.aatricks.novelscraper.ui.theme.NovelScraperTheme
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.util.FileUtils
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

import androidx.hilt.navigation.compose.hiltViewModel
import io.aatricks.novelscraper.ui.viewmodel.ExploreViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val readerViewModel: ReaderViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    
    @Inject lateinit var contentRepository: ContentRepository
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var exploreRepository: ExploreRepository
    @Inject lateinit var okHttpClient: OkHttpClient

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkForLibraryUpdates()

        setContent {
            NovelScraperTheme(
                darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                dynamicColor = true
            ) {
                val navController = rememberNavController()
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
                    composable<ExploreRoute> {
                        val exploreViewModel: ExploreViewModel = hiltViewModel()
                        ExploreScreen(
                            exploreViewModel = exploreViewModel,
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

    private fun checkForLibraryUpdates() {
        val prefs = io.aatricks.novelscraper.data.local.PreferencesManager(applicationContext)
        lifecycleScope.launch {
            try {
                libraryRepository.refreshLibraryUpdates(exploreRepository)
                prefs.lastUpdateCheckTime = System.currentTimeMillis()
            } catch (_: Exception) {}
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        handleWebUrl(uri.toString())
                    } else {
                        handleFilePicked(uri)
                    }
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        val urlPattern = Regex("https?://[^\\s]+")
                        urlPattern.find(sharedText)?.value?.let { handleWebUrl(it) }
                    }
                }
            }
        }
    }

    private fun handleWebUrl(url: String) {
        val title = io.aatricks.novelscraper.util.TextUtils.extractTitleFromUrl(url)
        libraryViewModel.addItem(title = title, url = url, contentType = ContentType.WEB)
        readerViewModel.loadContent(url)
    }

    private fun handleFilePicked(uri: Uri) {
        if (uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
        }
        
        val fileName = FileUtils.getFileName(this, uri) ?: "Unknown"
        val fileType = FileUtils.detectFileType(this, uri)
        val contentType = when (fileType) {
            FileUtils.FileType.PDF -> ContentType.PDF
            FileUtils.FileType.HTML -> ContentType.HTML
            FileUtils.FileType.EPUB -> ContentType.EPUB
            else -> {
                Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val title = fileName.substringBeforeLast('.')
        libraryViewModel.addItem(title = title, url = uri.toString(), contentType = contentType)

        if (contentType == ContentType.EPUB) {
            lifecycleScope.launch {
                try {
                    val epubBook = contentRepository.getEpubBook(uri.toString())
                    val firstHref = epubBook?.toc?.firstOrNull()?.href
                    if (firstHref != null) {
                        readerViewModel.loadEpubChapter(uri.toString(), firstHref, null)
                    } else {
                        readerViewModel.loadContent(uri.toString())
                    }
                } catch (e: Exception) {
                    readerViewModel.loadContent(uri.toString())
                }
            }
        } else {
            readerViewModel.loadContent(uri.toString())
        }
    }

    private fun checkPermissionsAndOpenFilePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openFilePicker()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun openFilePicker() {
        val mimeTypes = arrayOf("text/html", "application/xhtml+xml", "application/pdf", "application/epub+zip")
        filePickerLauncher.launch(mimeTypes)
    }

    override fun onPause() {
        super.onPause()
        try {
            readerViewModel.updateReadingProgress(readerViewModel.uiState.value.scrollProgress)
        } catch (_: Exception) {}
    }
}
