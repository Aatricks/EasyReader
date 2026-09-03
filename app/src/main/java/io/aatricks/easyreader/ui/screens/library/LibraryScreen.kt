package io.aatricks.easyreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.seriesReadingStatus
import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.ui.ExploreRoute
import io.aatricks.easyreader.ui.SettingsRoute
import io.aatricks.easyreader.ui.components.ChapterSummaryDropdown
import io.aatricks.easyreader.ui.components.LoadingTile
import io.aatricks.easyreader.ui.theme.EasyReaderMotion
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import io.aatricks.easyreader.ui.viewmodel.OpenNextChapterState
import io.aatricks.easyreader.ui.viewmodel.ReaderViewModel
import io.aatricks.easyreader.ui.viewmodel.SummaryViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    onNavigateBack: () -> Unit
): Unit {
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val readerUiState by readerViewModel.uiState.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val pendingDeletion by libraryViewModel.pendingDeletion.collectAsState()
    val statusFilter by libraryViewModel.statusFilter.collectAsState()
    val sortMode by libraryViewModel.sortMode.collectAsState()
    val groupBySource by libraryViewModel.groupBySource.collectAsState()
    val isRefreshing by libraryViewModel.isRefreshing.collectAsState()
    val openNextChapterState by libraryViewModel.openNextChapterState.collectAsState()
    val downloadRetryPrompt by libraryViewModel.downloadRetryPrompt.collectAsState()
    val summaryViewModel: SummaryViewModel = hiltViewModel()
    val summaryUiState by summaryViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var urlInput by remember { mutableStateOf("") }
    var isAddSectionVisible by remember { mutableStateOf(false) }
    val totalNovelCount = remember(libraryUiState.items) {
        countDistinctNovelTitles(libraryUiState.items)
    }
    val visibleNovelCount = remember(libraryUiState.filteredItems) {
        countDistinctNovelTitles(libraryUiState.filteredItems)
    }

    LaunchedEffect(Unit) {
        summaryViewModel.initializeSummaryService()
        libraryViewModel.reconcileDownloadedItemsOnDemand()
        libraryViewModel.backfillMissingCovers()
    }

    LaunchedEffect(pendingDeletion) {
        if (pendingDeletion.isNotEmpty()) {
            val count = pendingDeletion.size
            val label = if (count == 1) "1 title removed" else "$count titles removed"
            val result = snackbarHostState.showSnackbar(
                message = label,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                libraryViewModel.undoPendingDeletion()
            }
        }
    }

    LaunchedEffect(libraryUiState.snackbarMessage, libraryUiState.error) {
        libraryUiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            libraryViewModel.consumeSnackbarMessage()
        }
        libraryUiState.error?.let { err ->
            snackbarHostState.showSnackbar(message = err, duration = SnackbarDuration.Short)
            libraryViewModel.consumeError()
        }
    }

    // The typed URL survives a failed add, so the user can correct it instead of retyping.
    var addInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(libraryUiState.isLoading) {
        if (libraryUiState.isLoading) {
            addInFlight = true
        } else if (addInFlight) {
            addInFlight = false
            if (libraryUiState.error == null) {
                urlInput = ""
                isAddSectionVisible = false
            }
        }
    }

    LaunchedEffect(openNextChapterState) {
        (openNextChapterState as? OpenNextChapterState.Error)?.let { state ->
            snackbarHostState.showSnackbar(message = state.message, duration = SnackbarDuration.Short)
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

    // Back exits selection mode or clears the search before it leaves the screen, matching the
    // reader and Explore.
    BackHandler(enabled = libraryUiState.isSelectionMode || searchQuery.isNotBlank()) {
        if (libraryUiState.isSelectionMode) {
            libraryViewModel.clearSelection()
        } else {
            libraryViewModel.updateSearchQuery("")
        }
    }

    var showDownloadAllConfirmation by remember { mutableStateOf(false) }
    if (showDownloadAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadAllConfirmation = false },
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            title = { Text("Download all chapters?") },
            text = {
                Text(
                    "This queues ${formatLibraryCount(libraryUiState.items.size, "chapter")} for " +
                        "offline reading. It may use a lot of data and storage."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadAllConfirmation = false
                    libraryViewModel.prefetchLibrary()
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to reader"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(ExploreRoute) }) {
                        Icon(
                            imageVector = Icons.Default.TravelExplore,
                            contentDescription = "Explore sources"
                        )
                    }
                    IconButton(onClick = { isAddSectionVisible = !isAddSectionVisible }) {
                        Icon(
                            imageVector = if (isAddSectionVisible) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (isAddSectionVisible) "Hide add tools" else "Add or import"
                        )
                    }
                    LibraryOverflowMenu(
                        sortMode = sortMode,
                        onSortModeSelected = { libraryViewModel.setSortMode(it) },
                        groupBySource = groupBySource,
                        onGroupBySourceChanged = { libraryViewModel.setGroupBySource(it) },
                        onDownloadAll = { showDownloadAllConfirmation = true }
                    )
                    IconButton(onClick = { navController.navigate(SettingsRoute) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
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
                .background(MaterialTheme.colorScheme.surface)
                .padding(paddingValues)
                .imePadding()
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.md)
        ) {
            if (openNextChapterState is OpenNextChapterState.Loading || libraryUiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
            }

            AnimatedVisibility(
                visible = isAddSectionVisible,
                enter = expandVertically(animationSpec = tween(EasyReaderMotion.medium)) + fadeIn(animationSpec = tween(EasyReaderMotion.short)),
                exit = shrinkVertically(animationSpec = tween(EasyReaderMotion.short)) + fadeOut(animationSpec = tween(EasyReaderMotion.short))
            ) {
                AddNovelSection(
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    onAddClick = { libraryViewModel.fetchAndAdd(urlInput) },
                    onOpenPdfClick = {
                        onNavigateBack()
                        onOpenFilePicker()
                    }
                )
            }

            if (isAddSectionVisible) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
            }

            SearchLibraryField(
                query = searchQuery,
                onQueryChange = { libraryViewModel.updateSearchQuery(it) }
            )

            if (libraryUiState.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                LibraryStatusRow(
                    query = searchQuery,
                    totalCount = totalNovelCount,
                    visibleCount = visibleNovelCount,
                    isSelectionMode = libraryUiState.isSelectionMode,
                    selectedCount = libraryUiState.selectedCount,
                    onSelectionClick = {
                        if (libraryUiState.isSelectionMode) {
                            libraryViewModel.clearSelection()
                        } else {
                            libraryViewModel.enterSelectionMode()
                        }
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.md))
            }

            if (libraryUiState.isSelectionMode) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                SelectionActions(
                    selectedCount = libraryUiState.selectedCount,
                    onDelete = { libraryViewModel.removeSelectedItems() },
                    onSelectAll = { libraryViewModel.selectAll() },
                    onDownload = { libraryViewModel.prefetchLibrary(selectedOnly = true) }
                )
            }

            if (libraryUiState.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                ReadingStatusFilterRow(
                    selected = statusFilter,
                    counts = remember(libraryUiState.items) { computeStatusCounts(libraryUiState.items) },
                    onSelect = { libraryViewModel.setStatusFilter(it) }
                )
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                state = pullState,
                onRefresh = { libraryViewModel.refreshUpdates() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    // Waiting on the first database read, not an empty shelf: showing the empty
                    // card here flashes "Your library is empty" on every cold start.
                    !libraryUiState.hasLoaded -> LoadingTile(modifier = Modifier.fillMaxWidth())
                    libraryUiState.items.isEmpty() -> EmptyLibraryState(
                        onBrowseSources = { navController.navigate(ExploreRoute) },
                        onImportFile = {
                            onNavigateBack()
                            onOpenFilePicker()
                        }
                    )
                    libraryUiState.filteredItems.isEmpty() &&
                        (searchQuery.isNotBlank() || statusFilter != SeriesReadingStatus.ALL) ->
                        EmptyLibraryState(
                            onClearSearch = {
                                libraryViewModel.updateSearchQuery("")
                                libraryViewModel.setStatusFilter(SeriesReadingStatus.ALL)
                            },
                            query = searchQuery,
                            statusFilterLabel = statusFilter.takeIf {
                                searchQuery.isBlank() && it != SeriesReadingStatus.ALL
                            }?.label
                        )
                    else -> LibraryItemList(
                        uiState = libraryUiState,
                        readerUiState = readerUiState,
                        summaryUiState = summaryUiState,
                        libraryViewModel = libraryViewModel,
                        readerViewModel = readerViewModel,
                        summaryViewModel = summaryViewModel,
                        onCloseLibrary = onNavigateBack
                    )
                }
            }
        }
    }
}

private fun computeStatusCounts(items: List<LibraryItem>): Map<SeriesReadingStatus, Int> {
    val series = items.groupBy { it.libraryNovelKey() }
    val perSeriesStatus = series.values.map { seriesReadingStatus(it) }
    return mapOf(
        SeriesReadingStatus.ALL to series.size,
        SeriesReadingStatus.READING to perSeriesStatus.count { it == SeriesReadingStatus.READING },
        SeriesReadingStatus.FINISHED to perSeriesStatus.count { it == SeriesReadingStatus.FINISHED },
        SeriesReadingStatus.UNREAD to perSeriesStatus.count { it == SeriesReadingStatus.UNREAD }
    )
}

@Composable
private fun ReadingStatusFilterRow(
    selected: SeriesReadingStatus,
    counts: Map<SeriesReadingStatus, Int>,
    onSelect: (SeriesReadingStatus) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Fades the trailing edge so a clipped last chip reads as "there is more to scroll".
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startX = size.width - CHIP_ROW_FADE_WIDTH_PX,
                        endX = size.width
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        SeriesReadingStatus.entries.forEach { filter ->
            val count = counts[filter] ?: 0
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = if (count > 0 || filter == SeriesReadingStatus.ALL)
                            "${filter.label} ($count)" else filter.label
                    )
                }
            )
        }
    }
}

@Composable
private fun AddNovelSection(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onOpenPdfClick: () -> Unit
): Unit {
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    LaunchedEffect(clipboard) {
        val entry = clipboard.getClipEntry()
        val raw = entry?.clipData?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        clipboardUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else null
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.md)) {
            Text(
                text = "Add from the web or import a file",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
            Text(
                text = "Paste a novel URL to add it now, or import a file from your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text("Web URL") },
                placeholder = { Text("https://...") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (urlInput.isNotEmpty()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear URL")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (urlInput.isNotBlank()) onAddClick() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )

            val url = clipboardUrl
            if (url != null && urlInput.isBlank()) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                AssistChip(
                    onClick = { onUrlChange(url) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Paste: ${url.take(48)}${if (url.length > 48) "…" else ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                Button(
                    onClick = onAddClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    enabled = urlInput.isNotBlank(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text("Add from web", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onOpenPdfClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text("Import file", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SearchLibraryField(
    query: String,
    onQueryChange: (String) -> Unit
): Unit {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search your library") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        singleLine = true
    )
}

@Composable
private fun LibraryStatusRow(
    query: String,
    totalCount: Int,
    visibleCount: Int,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSelectionClick: () -> Unit
): Unit {
    val statusText = when {
        isSelectionMode && selectedCount > 0 -> "$selectedCount selected"
        isSelectionMode -> "Select titles to remove or download"
        query.isNotBlank() -> "${formatLibraryCount(visibleCount, "result")} in view"
        else -> formatLibraryCount(totalCount, "title")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onSelectionClick) {
            Text(if (isSelectionMode) "Done" else "Select")
        }
    }
}

/**
 * Exiting selection mode lives on the "Done" button in [LibraryStatusRow]; this row carries only
 * the actions that operate on the selection.
 */
@Composable
private fun SelectionActions(
    selectedCount: Int,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit
): Unit {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = EasyReaderSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        Button(
            onClick = onDelete,
            enabled = selectedCount > 0,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            Text("Delete", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onDownload,
            enabled = selectedCount > 0,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            Text("Download", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onSelectAll,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            Text("Select all", fontWeight = FontWeight.SemiBold)
        }
    }
}



@Composable
private fun EmptyLibraryState(
    onClearSearch: () -> Unit = {},
    onBrowseSources: () -> Unit = {},
    onImportFile: () -> Unit = {},
    query: String = "",
    statusFilterLabel: String? = null
) {
    val isFilteredEmpty = query.isNotBlank() || statusFilterLabel != null
    val (headline, body) = emptyLibraryCopy(query, statusFilterLabel)
    Box(
        // Scrollable so pull-to-refresh still has a gesture to hook when the list is empty.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm),
                modifier = Modifier.padding(EasyReaderSpacing.xl)
            ) {
                val icon = if (isFilteredEmpty) Icons.Filled.SearchOff else Icons.AutoMirrored.Filled.LibraryBooks
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Text(text = headline, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (isFilteredEmpty) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    FilledTonalButton(onClick = onClearSearch) {
                        Text(if (statusFilterLabel != null) "Show all titles" else "Clear search")
                    }
                } else {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
                    ) {
                        Button(onClick = onBrowseSources) {
                            Text("Browse sources")
                        }
                        OutlinedButton(onClick = onImportFile) {
                            Text("Import file")
                        }
                    }
                }
            }
        }
    }
}

/** Headline and body for the empty shelf, the empty search and the empty status filter. */
private fun emptyLibraryCopy(query: String, statusFilterLabel: String?): Pair<String, String> = when {
    statusFilterLabel != null -> "Nothing in $statusFilterLabel" to
        "No titles have that status right now. Pick another filter to see everything."
    query.isNotBlank() -> "No matches for \"$query\"" to
        "No titles match your search. Try different words or clear the search to see everything."
    else -> "Your library is empty" to
        "Add a title from Explore or import a file to start building your shelf."
}

private const val CHIP_ROW_FADE_WIDTH_PX = 48f

private fun formatLibraryCount(count: Int, noun: String): String {
    val suffix = if (count == 1) noun else "${noun}s"
    return "$count $suffix"
}
