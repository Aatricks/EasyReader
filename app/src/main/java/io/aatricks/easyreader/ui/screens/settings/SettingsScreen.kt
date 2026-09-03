package io.aatricks.easyreader.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aatricks.easyreader.R
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
    // Scope this to the activity, not to the Settings back-stack entry: the update dialog
    // lives at the root of MainActivity and only ever sees the activity-scoped instance.
    val updateViewModel: UpdateViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
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
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_update_check_error, err)
                )
            } else if (update == null) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_up_to_date, updateState.currentVersion)
                )
            }
            userTriggeredCheck = false
        }
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
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
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
                    ) {
                        val themes = listOf(
                            "SYSTEM" to stringResource(R.string.settings_theme_system),
                            "LIGHT" to stringResource(R.string.settings_theme_light),
                            "DARK" to stringResource(R.string.settings_theme_dark)
                        )
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
                                text = stringResource(R.string.settings_dynamic_color),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_dynamic_color_subtitle),
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
                        text = stringResource(R.string.settings_accent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (accentEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                    val chipScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .accentRowEndFade(chipScrollState.canScrollForward)
                            .horizontalScroll(chipScrollState),
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
                            text = stringResource(R.string.settings_accent_disabled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                SettingsRow(
                    title = stringResource(R.string.settings_cache_size),
                    subtitle = if (cacheBytes < 0) {
                        stringResource(R.string.settings_calculating)
                    } else {
                        formatBytes(cacheBytes)
                    }
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
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text(stringResource(R.string.settings_clear_cache_button))
                }
                SettingsRow(
                    title = stringResource(R.string.settings_downloads_size),
                    subtitle = if (downloadsBytes < 0) {
                        stringResource(R.string.settings_calculating)
                    } else {
                        formatBytes(downloadsBytes)
                    }
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
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text(stringResource(R.string.settings_clear_downloads_button))
                }
                val titlesCount = countDistinctNovelTitles(libraryState.items)
                val entriesCount = libraryState.items.size
                SettingsRow(
                    title = stringResource(R.string.settings_library_size),
                    subtitle = pluralStringResource(
                        R.plurals.settings_library_size_summary,
                        titlesCount,
                        titlesCount,
                        entriesCount
                    )
                )
                OutlinedButton(
                    onClick = { showClearLibraryDialog = true },
                    enabled = libraryState.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_clear_library_button))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (summaryUiState.supportsAi) {
                SettingsSection(title = stringResource(R.string.settings_section_ai)) {
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
                                text = stringResource(R.string.settings_ai_summaries),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (summaryUiState.isEnabled) {
                                    stringResource(R.string.settings_ai_summaries_on)
                                } else {
                                    stringResource(R.string.settings_ai_summaries_off)
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
                        SettingsRow(
                            title = stringResource(R.string.settings_status),
                            subtitle = stringResource(R.string.settings_status_downloading_model)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            SettingsSection(title = stringResource(R.string.settings_section_progression)) {
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
                            text = stringResource(R.string.settings_scroll),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (scrollEnabled) {
                                stringResource(R.string.settings_scroll_on)
                            } else {
                                stringResource(R.string.settings_scroll_off)
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

            SettingsSection(title = stringResource(R.string.settings_section_backup)) {
                val inProgress = backupStatus is BackupViewModel.OpStatus.InProgress
                SettingsRow(
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.settings_backup_settings_subtitle)
                )
                FilledTonalButton(
                    onClick = { exportSettingsLauncher.launch(defaultSettingsFilename()) },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_export_settings))
                }
                OutlinedButton(
                    onClick = { importSettingsLauncher.launch(arrayOf("application/json")) },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_import_settings))
                }
                SettingsRow(
                    title = stringResource(R.string.library_title),
                    subtitle = stringResource(R.string.settings_backup_library_subtitle)
                )
                FilledTonalButton(
                    onClick = { exportLibraryLauncher.launch(defaultLibraryFilename()) },
                    enabled = !inProgress && libraryState.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_export_library))
                }
                OutlinedButton(
                    onClick = {
                        importLibraryLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_import_library))
                }
                if (inProgress) {
                    SettingsRow(
                        title = stringResource(R.string.settings_status),
                        subtitle = stringResource(R.string.settings_status_working)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    title = stringResource(R.string.settings_app),
                    subtitle = stringResource(R.string.settings_app_version, updateState.currentVersion)
                )
                SettingsRow(title = stringResource(R.string.settings_license), subtitle = "GPL-3.0")

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
                            text = stringResource(R.string.settings_auto_update),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_auto_update_subtitle),
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
                    SettingsRow(
                        title = stringResource(R.string.settings_status),
                        subtitle = stringResource(R.string.settings_status_checking_updates)
                    )
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
                                stringResource(R.string.settings_status_downloading_update_percent, percent)
                            } else {
                                stringResource(R.string.settings_status_downloading_update)
                            }
                            SettingsRow(
                                title = stringResource(R.string.settings_status),
                                subtitle = progressText
                            )
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
                                Text(stringResource(R.string.settings_install_update))
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
                                Text(stringResource(R.string.settings_check_updates))
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
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text(stringResource(R.string.settings_open_github))
                }
            }
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_clear_cache_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_clear_cache_dialog_body,
                        formatBytes(cacheBytes)
                    )
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showClearCacheDialog = false
                    scope.launch {
                        val outcome = readerViewModel.clearAllCache()
                        refreshKey++
                        snackbarHostState.showSnackbar(
                            if (outcome.isSuccess) {
                                context.getString(R.string.settings_cache_cleared)
                            } else {
                                context.getString(
                                    R.string.settings_cache_clear_failed,
                                    outcome.exceptionOrNull()?.message
                                )
                            }
                        )
                    }
                }) {
                    Text(stringResource(R.string.common_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showClearDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsDialog = false },
            title = { Text(stringResource(R.string.settings_clear_downloads_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_clear_downloads_dialog_body,
                        formatBytes(downloadsBytes)
                    )
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    showClearDownloadsDialog = false
                    scope.launch {
                        val outcome = libraryViewModel.clearAllDownloads()
                        refreshKey++
                        snackbarHostState.showSnackbar(
                            if (outcome.isSuccess) {
                                context.getString(R.string.settings_downloads_cleared)
                            } else {
                                context.getString(
                                    R.string.settings_downloads_clear_failed,
                                    outcome.exceptionOrNull()?.message
                                )
                            }
                        )
                    }
                }) {
                    Text(stringResource(R.string.common_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showEnableAiDialog) {
        AlertDialog(
            onDismissRequest = { showEnableAiDialog = false },
            title = { Text(stringResource(R.string.settings_enable_ai_dialog_title)) },
            text = { Text(stringResource(R.string.settings_enable_ai_dialog_body)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    summaryViewModel.setAiSummaryEnabled(true)
                    showEnableAiDialog = false
                }) {
                    Text(stringResource(R.string.settings_enable_ai_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableAiDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showClearLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showClearLibraryDialog = false },
            title = { Text(stringResource(R.string.settings_clear_library_dialog_title)) },
            text = { Text(stringResource(R.string.settings_clear_library_dialog_body)) },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showClearLibraryDialog = false
                        scope.launch {
                            val outcome = libraryViewModel.clearLibrary()
                            refreshKey++
                            snackbarHostState.showSnackbar(
                                if (outcome.isSuccess) {
                                    context.getString(R.string.settings_library_cleared)
                                } else {
                                    context.getString(
                                        R.string.settings_library_clear_failed,
                                        outcome.exceptionOrNull()?.message
                                    )
                                }
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingSettingsImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingSettingsImportUri = null },
            title = { Text(stringResource(R.string.settings_restore_settings_dialog_title)) },
            text = { Text(stringResource(R.string.settings_restore_settings_dialog_body)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    backupViewModel.importSettings(uri)
                    pendingSettingsImportUri = null
                }) {
                    Text(stringResource(R.string.settings_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSettingsImportUri = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingLibraryImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLibraryImportUri = null },
            title = { Text(stringResource(R.string.settings_restore_library_dialog_title)) },
            text = { Text(stringResource(R.string.settings_restore_library_dialog_body)) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    backupViewModel.importLibrary(uri)
                    pendingLibraryImportUri = null
                }) {
                    Text(stringResource(R.string.settings_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLibraryImportUri = null }) {
                    Text(stringResource(R.string.common_cancel))
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
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

/** Fades the trailing edge of the accent chip row so a clipped last chip reads as scrollable. */
private fun Modifier.accentRowEndFade(visible: Boolean): Modifier =
    if (!visible) this else this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.horizontalGradient(
                    START_FADE_STOP to Color.Black,
                    1f to Color.Transparent,
                    startX = size.width - ACCENT_FADE_WIDTH_PX,
                    endX = size.width
                ),
                blendMode = BlendMode.DstIn
            )
        }

private const val ACCENT_FADE_WIDTH_PX = 48f
private const val START_FADE_STOP = 0f
