package io.aatricks.easyreader.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import io.aatricks.easyreader.ui.screens.countDistinctNovelTitles
import androidx.hilt.navigation.compose.hiltViewModel
import io.aatricks.easyreader.ui.theme.AccentTheme
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.BackupViewModel
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.SummaryViewModel
import io.aatricks.easyreader.ui.viewmodel.UpdateViewModel
import io.aatricks.easyreader.updater.DownloadStatus
import io.aatricks.easyreader.updater.UpdateCheckResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    backupViewModel: BackupViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val libraryState by libraryViewModel.uiState.collectAsState()
    val backupStatus by backupViewModel.status.collectAsState()
    val summaryViewModel: SummaryViewModel = hiltViewModel()
    val summaryUiState by summaryViewModel.uiState.collectAsState()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.uiState.collectAsState()
    val appearanceSettings by settingsViewModel.appearanceSettings.collectAsState()
    val readerSettings by settingsViewModel.readerSettings.collectAsState()

    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var downloadsBytes by remember { mutableLongStateOf(-1L) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearDownloadsDialog by remember { mutableStateOf(false) }
    var showClearLibraryDialog by remember { mutableStateOf(false) }
    var showEnableAiDialog by remember { mutableStateOf(false) }
    var userTriggeredCheck by remember { mutableStateOf(false) }
    var pendingSettingsImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingLibraryImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(backupViewModel::exportSettings) }

    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingSettingsImportUri = uri }

    val exportLibraryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(backupViewModel::exportLibrary) }

    val importLibraryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingLibraryImportUri = uri }

    LaunchedEffect(refreshKey) {
        cacheBytes = runCatching { readerViewModel.getCacheSize() }.getOrDefault(0L)
        downloadsBytes = runCatching { readerViewModel.getDownloadsSize() }.getOrDefault(0L)
    }

    LaunchedEffect(backupStatus) {
        when (val s = backupStatus) {
            is BackupViewModel.OpStatus.Success -> {
                snackbarHostState.showSnackbar(s.message)
                backupViewModel.ackStatus()
                refreshKey++
            }
            is BackupViewModel.OpStatus.Error -> {
                snackbarHostState.showSnackbar(s.message)
                backupViewModel.ackStatus()
            }
            else -> Unit
        }
    }

    LaunchedEffect(updateState.isChecking) {
        if (userTriggeredCheck && !updateState.isChecking) {
            val err = updateState.error
            val update = updateState.updateAvailable
            if (err != null) {
                snackbarHostState.showSnackbar("Error checking updates: $err")
            } else if (update == null) {
                snackbarHostState.showSnackbar("Emaki is up to date (${updateState.currentVersion})")
            }
            userTriggeredCheck = false
        }
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.md),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
        ) {
            SettingsSection(title = "Appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                    ) {
                        val themes = listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark")
                        themes.forEach { (mode, label) ->
                            FilterChip(
                                selected = appearanceSettings.themeMode == mode,
                                onClick = { settingsViewModel.setThemeMode(mode) },
                                label = {
                                    Text(
                                        text = label,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = settingsChipColors()
                            )
                        }
                    }
                }

                val showDynamicColor = android.os.Build.VERSION.SDK_INT >= ANDROID_12_SDK_INT
                if (showDynamicColor) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = appearanceSettings.dynamicColor,
                                role = Role.Switch,
                                onValueChange = { settingsViewModel.setDynamicColor(it) }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dynamic color",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Use wallpaper colors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.size(EasyReaderSpacing.sm))
                        Switch(
                            checked = appearanceSettings.dynamicColor,
                            onCheckedChange = null
                        )
                    }
                }

                val accentEnabled = !appearanceSettings.dynamicColor
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Text(
                        text = "Accent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (accentEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                    ) {
                        AccentTheme.entries.forEach { accentTheme ->
                            AccentThemeChip(
                                accentTheme = accentTheme,
                                isSelected = readerSettings.accentTheme == accentTheme.name,
                                enabled = accentEnabled,
                                onClick = { settingsViewModel.setAccentTheme(accentTheme) }
                            )
                        }
                    }
                    if (!accentEnabled) {
                        Text(
                            text = "Dynamic color is active. Disable it to customize accent color.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSection(title = "Storage") {
                SettingsRow(
                    title = "Cache size",
                    subtitle = if (cacheBytes < 0) "Calculating…" else formatBytes(cacheBytes)
                )
                FilledTonalButton(
                    onClick = { showClearCacheDialog = true },
                    enabled = cacheBytes > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    Text("Clear cached chapters and images")
                }
                SettingsRow(
                    title = "Downloads size",
                    subtitle = if (downloadsBytes < 0) "Calculating…" else formatBytes(downloadsBytes)
                )
                FilledTonalButton(
                    onClick = { showClearDownloadsDialog = true },
                    enabled = downloadsBytes > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    Text("Clear all downloads")
                }
                val titlesCount = countDistinctNovelTitles(libraryState.items)
                val entriesCount = libraryState.items.size
                SettingsRow(
                    title = "Library size",
                    subtitle = if (titlesCount == 1) {
                        "1 title · $entriesCount entries"
                    } else {
                        "$titlesCount titles · $entriesCount entries"
                    }
                )
                OutlinedButton(
                    onClick = { showClearLibraryDialog = true },
                    enabled = libraryState.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear entire library")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (summaryUiState.supportsAi) {
                SettingsSection(title = "AI features") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = summaryUiState.isEnabled,
                                role = Role.Switch,
                                onValueChange = { wantEnabled ->
                                    if (wantEnabled && !summaryUiState.isEnabled) {
                                        showEnableAiDialog = true
                                    } else if (!wantEnabled) {
                                        summaryViewModel.setAiSummaryEnabled(false)
                                    }
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Chapter summaries",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (summaryUiState.isEnabled) {
                                    "Model runs on-device. Disable to free memory."
                                } else {
                                    "Downloads a small on-device model (a few hundred MB)."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.size(EasyReaderSpacing.sm))
                        Switch(
                            checked = summaryUiState.isEnabled,
                            onCheckedChange = null
                        )
                    }
                    if (summaryUiState.isInitializing) {
                        SettingsRow(title = "Status", subtitle = "Downloading model…")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            SettingsSection(title = "Progression") {
                val scrollViewModel: io.aatricks.easyreader.ui.viewmodel.ScrollViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val scrollEnabled by scrollViewModel.gamificationEnabled.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = scrollEnabled,
                            role = Role.Switch,
                            onValueChange = { scrollViewModel.setGamificationEnabled(it) }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "The Scroll",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (scrollEnabled) {
                                "Reading time, levels, and milestones are tracked."
                            } else {
                                "Tracking is off. Existing progress is kept."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.size(EasyReaderSpacing.sm))
                    Switch(
                        checked = scrollEnabled,
                        onCheckedChange = null
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSection(title = "Backup & restore") {
                val inProgress = backupStatus is BackupViewModel.OpStatus.InProgress
                SettingsRow(
                    title = "Settings",
                    subtitle = "Reader font, theme, margins"
                )
                FilledTonalButton(
                    onClick = { exportSettingsLauncher.launch(defaultSettingsFilename()) },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export settings")
                }
                OutlinedButton(
                    onClick = { importSettingsLauncher.launch(arrayOf("application/json")) },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import settings")
                }
                SettingsRow(
                    title = "Library",
                    subtitle = "Titles, progress, bundled EPUBs"
                )
                FilledTonalButton(
                    onClick = { exportLibraryLauncher.launch(defaultLibraryFilename()) },
                    enabled = !inProgress && libraryState.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export library")
                }
                OutlinedButton(
                    onClick = {
                        importLibraryLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import library")
                }
                if (inProgress) {
                    SettingsRow(title = "Status", subtitle = "Working…")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSection(title = "About") {
                SettingsRow(title = "App", subtitle = "Emaki (${updateState.currentVersion})")
                SettingsRow(title = "License", subtitle = "GPL-3.0")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = updateState.automaticUpdateChecksEnabled,
                            role = Role.Switch,
                            onValueChange = updateViewModel::setAutomaticUpdateChecksEnabled
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatically check for updates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Check once a day when Emaki opens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.size(EasyReaderSpacing.sm))
                    Switch(
                        checked = updateState.automaticUpdateChecksEnabled,
                        onCheckedChange = null
                    )
                }
                
                val isChecking = updateState.isChecking
                val downloadStatus = updateState.downloadStatus
                
                if (isChecking) {
                    SettingsRow(title = "Status", subtitle = "Checking for updates…")
                } else {
                    when (downloadStatus) {
                        is DownloadStatus.Progress -> {
                            val percent = if (downloadStatus.totalBytes > 0) {
                                val bytes = downloadStatus.bytesDownloaded
                                val total = downloadStatus.totalBytes
                                (bytes * PERCENT_MULTIPLIER / total).toInt()
                            } else {
                                -1
                            }
                            val progressText = if (percent >= 0) {
                                "Downloading update ($percent%)…"
                            } else {
                                "Downloading update…"
                            }
                            SettingsRow(title = "Status", subtitle = progressText)
                        }
                        is DownloadStatus.Success -> {
                            FilledTonalButton(
                                onClick = {
                                    if (updateViewModel.canInstallPackages()) {
                                        updateViewModel.installApk(downloadStatus.apkFile)
                                    } else {
                                        val intent = updateViewModel.requestInstallPermissionIntent()
                                        if (intent != null) {
                                            runCatching { context.startActivity(intent) }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install downloaded update")
                            }
                        }
                        else -> {
                            FilledTonalButton(
                                onClick = {
                                    userTriggeredCheck = true
                                    updateViewModel.checkForUpdates()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Check for updates")
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Aatricks/EasyReader")
                            )
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    Text("Open project on GitHub")
                }
            }
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear cache?") },
            text = {
                Text(
                    "This frees ${formatBytes(cacheBytes)} of cached chapters and images. " +
                        "Your library and reading progress are not affected."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    readerViewModel.clearAllCache()
                    showClearCacheDialog = false
                    refreshKey++
                    scope.launch { snackbarHostState.showSnackbar("Cache cleared") }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsDialog = false },
            title = { Text("Clear downloads?") },
            text = {
                Text(
                    "This frees ${formatBytes(downloadsBytes)} of offline downloads. " +
                        "Your library and reading progress are not affected."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    libraryViewModel.clearAllDownloads()
                    showClearDownloadsDialog = false
                    refreshKey++
                    scope.launch { snackbarHostState.showSnackbar("Downloads cleared") }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEnableAiDialog) {
        AlertDialog(
            onDismissRequest = { showEnableAiDialog = false },
            title = { Text("Enable AI summaries?") },
            text = {
                Text(
                    "This downloads a small on-device language model (a few hundred MB) " +
                        "the first time it is needed. After that it runs offline. " +
                        "You can disable AI summaries at any time."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    summaryViewModel.setAiSummaryEnabled(true)
                    showEnableAiDialog = false
                }) {
                    Text("Download and enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableAiDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showClearLibraryDialog = false },
            title = { Text("Clear entire library?") },
            text = {
                Text(
                    "This permanently removes every title and all reading progress. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        libraryViewModel.clearLibrary()
                        showClearLibraryDialog = false
                        refreshKey++
                        scope.launch { snackbarHostState.showSnackbar("Library cleared") }
                    }
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingSettingsImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingSettingsImportUri = null },
            title = { Text("Restore settings?") },
            text = {
                Text(
                    "This replaces your current reader font, theme, and margins " +
                        "with the values from the backup."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    backupViewModel.importSettings(uri)
                    pendingSettingsImportUri = null
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSettingsImportUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingLibraryImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLibraryImportUri = null },
            title = { Text("Restore library?") },
            text = {
                Text(
                    "Imports titles, progress, and bundled EPUBs. " +
                        "Existing titles whose URL matches a backup entry are skipped."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    backupViewModel.importLibrary(uri)
                    pendingLibraryImportUri = null
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLibraryImportUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }


}

private fun defaultSettingsFilename(): String =
    "easyreader-settings-${todayStamp()}.json"

private fun defaultLibraryFilename(): String =
    "easyreader-library-${todayStamp()}.zip"

private fun todayStamp(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

@Composable
private fun settingsChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun AccentThemeChip(
    accentTheme: AccentTheme,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(accentTheme.displayName) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) accentTheme.previewColor
                        else accentTheme.previewColor.copy(alpha = 0.38f)
                    )
            )
        },
        colors = settingsChipColors()
    )
}

private const val PERCENT_MULTIPLIER = 100
private const val ANDROID_12_SDK_INT = 31
