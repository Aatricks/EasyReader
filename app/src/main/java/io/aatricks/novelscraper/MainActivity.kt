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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.ui.screens.ReaderScreen
import io.aatricks.novelscraper.ui.theme.NovelScraperTheme
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.util.FileUtils

/**
 * Main Activity for Novel Scraper app.
 * 
 * Features:
 * - Edge-to-edge display with proper theming
 * - File picker for HTML/PDF files
 * - ViewModel initialization for reader and library
 * - Permission handling for storage access
 * - Deep link handling for web URLs
 */
class MainActivity : ComponentActivity() {

    // ViewModels
    private lateinit var readerViewModel: ReaderViewModel
    private lateinit var libraryViewModel: LibraryViewModel

    // Repositories
    private lateinit var contentRepository: ContentRepository
    private lateinit var libraryRepository: LibraryRepository

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleFilePicked(it) }
    }

    // Permission launcher for storage access (Android 13+)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(
                this,
                "Storage permission is required to access files",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()

        // Initialize repositories
        initializeRepositories()

        // Initialize ViewModels
        initializeViewModels()

        // Set up the UI
        setContent {
            NovelScraperTheme(
                darkTheme = true, // Force dark theme for reading
                dynamicColor = false // Disable dynamic colors for consistent theme
            ) {
                ReaderScreen(
                    readerViewModel = readerViewModel,
                    libraryViewModel = libraryViewModel,
                    onOpenFilePicker = { checkPermissionsAndOpenFilePicker() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Handle intent (deep links, file opens)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Initialize repositories with application context
     */
    private fun initializeRepositories() {
        contentRepository = ContentRepository(applicationContext)
        val preferencesManager = io.aatricks.novelscraper.data.local.PreferencesManager(applicationContext)
        libraryRepository = LibraryRepository(preferencesManager)
    }

    /**
     * Initialize ViewModels with repositories
     */
    private fun initializeViewModels() {
        // Create ViewModelFactory
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>
            ): T {
                return when {
                    modelClass.isAssignableFrom(ReaderViewModel::class.java) -> {
                        ReaderViewModel(contentRepository, libraryRepository) as T
                    }
                    modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                        LibraryViewModel(libraryRepository) as T
                    }
                    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }

        // Initialize ViewModels
        readerViewModel = ViewModelProvider(this, factory)[ReaderViewModel::class.java]
        libraryViewModel = ViewModelProvider(this, factory)[LibraryViewModel::class.java]
    }

    /**
     * Handle incoming intents (deep links, file opens)
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Handle file open or web URL
                intent.data?.let { uri ->
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        // Web URL - load in reader
                        handleWebUrl(uri.toString())
                    } else {
                        // File URI - handle as file
                        handleFilePicked(uri)
                    }
                }
            }
            Intent.ACTION_SEND -> {
                // Handle shared URL
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        // Try to extract URL from shared text
                        val urlPattern = Regex("https?://[^\\s]+")
                        val matchResult = urlPattern.find(sharedText)
                        matchResult?.value?.let { url ->
                            handleWebUrl(url)
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle web URL loading
     */
    private fun handleWebUrl(url: String) {
        // Extract title from URL
        val title = io.aatricks.novelscraper.util.TextUtils.extractTitleFromUrl(url)
        
        // Add to library if not exists
        libraryViewModel.addItem(
            title = title,
            url = url,
            contentType = ContentType.Web
        )

        // Load content in reader
        readerViewModel.loadContent(url)
    }

    /**
     * Handle picked file from file picker
     */
    private fun handleFilePicked(uri: Uri) {
        // Get file information
        val fileName = FileUtils.getFileName(this, uri) ?: "Unknown"
        val fileType = FileUtils.detectFileType(this, uri)

        // Determine content type
        val contentType = when (fileType) {
            FileUtils.FileType.PDF -> ContentType.PDF
            FileUtils.FileType.HTML -> ContentType.HTML
            FileUtils.FileType.EPUB -> ContentType.HTML
            else -> {
                Toast.makeText(
                    this,
                    "Unsupported file type",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        // Add to library
        val title = fileName.substringBeforeLast('.')
        libraryViewModel.addItem(
            title = title,
            url = uri.toString(),
            contentType = contentType
        )

        // Load content in reader
        readerViewModel.loadContent(uri.toString())

        Toast.makeText(
            this,
            "Loaded: $fileName",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Check permissions and open file picker
     */
    private fun checkPermissionsAndOpenFilePicker() {
        // On Android 13+ (API 33+), we need READ_MEDIA_* permissions
        // On Android 10-12 (API 29-32), we can use scoped storage without permissions
        // On Android 9 and below, we need READ_EXTERNAL_STORAGE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - check for READ_MEDIA_DOCUMENTS
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openFilePicker()
            } else {
                // Request permission
                storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 - scoped storage, no permission needed
            openFilePicker()
        } else {
            // Android 9 and below - check READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openFilePicker()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * Open the file picker
     */
    private fun openFilePicker() {
        val mimeTypes = arrayOf(
            "text/html",
            "application/xhtml+xml",
            "application/pdf",
            "application/epub+zip"
        )
        
        filePickerLauncher.launch(mimeTypes)
    }

    override fun onPause() {
        super.onPause()
        // Save reading progress when app goes to background
        readerViewModel.updateReadingProgress(
            readerViewModel.uiState.value.scrollProgress
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up ViewModels if needed
    }
}