package io.aatricks.easyreader.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
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
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val libraryState by libraryViewModel.uiState.collectAsState()

    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearLibraryDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        cacheBytes = runCatching { readerViewModel.getCacheSize() }.getOrDefault(0L)
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
                    title = "Library size",
                    subtitle = "${libraryState.items.size} items"
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

            SettingsSection(title = "About") {
                SettingsRow(title = "App", subtitle = "EasyReader")
                SettingsRow(title = "License", subtitle = "GPL-3.0")
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
}

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

