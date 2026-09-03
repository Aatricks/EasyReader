package io.aatricks.easyreader.ui.screens.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.content.Context
import io.aatricks.easyreader.R
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.repository.source.BrowseMode
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing
import io.aatricks.easyreader.ui.viewmodel.ExploreViewModel
import io.aatricks.easyreader.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreViewModel: ExploreViewModel,
    libraryViewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onReadItem: (ExploreItem) -> Unit
): Unit {
    val uiState by exploreViewModel.uiState.collectAsState()
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFiltersSheet by remember { mutableStateOf(false) }
    val filtersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val hasActiveFilters = remember(uiState.searchQuery, uiState.selectedSource, uiState.selectedTags) {
        uiState.searchQuery.isNotBlank() || uiState.selectedSource != null || uiState.selectedTags.isNotEmpty()
    }

    val libraryUrls = remember(libraryUiState.items) {
        libraryUiState.items.map { it.url }.toSet()
    }
    val isInLibrary: (ExploreItem) -> Boolean = remember(libraryUrls) {
        { item -> item.url in libraryUrls || (item.readingUrl?.let { it in libraryUrls } == true) }
    }

    BackHandler {
        when {
            showFiltersSheet -> showFiltersSheet = false
            hasActiveFilters -> exploreViewModel.clearFilters()
            else -> onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            ExploreTopBar(
                hasActiveFilters = hasActiveFilters,
                onNavigateBack = onNavigateBack,
                onClearFilters = { exploreViewModel.clearFilters() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        ExploreContent(
            uiState = uiState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            hasActiveFilters = hasActiveFilters,
            onSearchQueryChange = { exploreViewModel.updateSearchQuery(it) },
            onPerformSearch = { exploreViewModel.performSearch() },
            onOpenFilters = { showFiltersSheet = true },
            onClearFilters = { exploreViewModel.clearFilters() },
            onSetBrowseMode = { exploreViewModel.setBrowseMode(it) },
            onSourceSelect = { exploreViewModel.selectSource(it) },
            onTagToggle = { exploreViewModel.toggleTag(it) },
            onItemSelect = { exploreViewModel.selectItem(it) },
            onLoadMore = { exploreViewModel.loadMore() },
            onRetryFailedSource = { exploreViewModel.retryFailedSearchSource(it) }
        )
    }

    if (showFiltersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFiltersSheet = false },
            sheetState = filtersSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            FiltersBottomSheetContent(
                uiState = uiState,
                onSourceSelect = { exploreViewModel.selectSource(it) },
                onTagToggle = { exploreViewModel.toggleTag(it) },
                onClearTags = { exploreViewModel.clearTags() },
                onClose = { showFiltersSheet = false }
            )
        }
    }

    val activeItem = uiState.selectedItemDetails ?: uiState.selectedItem
    if (activeItem != null) {
        ModalBottomSheet(
            onDismissRequest = { exploreViewModel.dismissItem() },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            ExploreItemDetailSheet(
                uiState = uiState,
                isInLibrary = isInLibrary(activeItem),
                onRetryDetails = { exploreViewModel.selectItem(activeItem) },
                onAddToLibrary = {
                    exploreViewModel.dismissItem()
                    scope.launch {
                        saveExploreItem(context, activeItem, libraryViewModel, snackbarHostState, onOpenLibrary)
                    }
                },
                onRead = {
                    val alreadySaved = isInLibrary(activeItem)
                    exploreViewModel.dismissItem()
                    scope.launch {
                        readExploreItem(context, activeItem, alreadySaved, libraryViewModel, onReadItem)
                            ?.let { snackbarHostState.showSnackbar(it) }
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreTopBar(
    hasActiveFilters: Boolean,
    onNavigateBack: () -> Unit,
    onClearFilters: () -> Unit
): Unit {
    TopAppBar(
        title = { Text(stringResource(R.string.explore_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
        },
        actions = {
            if (hasActiveFilters) {
                TextButton(onClick = onClearFilters) {
                    Text(stringResource(R.string.explore_reset))
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreContent(
    uiState: ExploreViewModel.ExploreUiState,
    modifier: Modifier = Modifier,
    hasActiveFilters: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onClearFilters: () -> Unit,
    onSetBrowseMode: (BrowseMode) -> Unit,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onItemSelect: (ExploreItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetryFailedSource: (String) -> Unit
): Unit {
    val gridState = rememberLazyGridState()

    LaunchedEffect(uiState.searchQuery, uiState.browseMode, uiState.selectedSource, uiState.selectedTags) {
        gridState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .imePadding()
            .padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)
    ) {
        SearchField(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChange,
            onPerformSearch = onPerformSearch
        )

        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

        ActiveFilterBar(
            uiState = uiState,
            hasActiveFilters = hasActiveFilters,
            onOpenFilters = onOpenFilters,
            onSourceSelect = onSourceSelect,
            onTagToggle = onTagToggle,
            onClearFilters = onClearFilters
        )

        if (uiState.searchQuery.isBlank()) {
            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
            BrowseModeTabs(
                selected = uiState.browseMode,
                onSelect = onSetBrowseMode
            )
        }

        Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

        ExploreGrid(
            gridState = gridState,
            modifier = Modifier.weight(1f),
            uiState = uiState,
            hasActiveFilters = hasActiveFilters,
            onItemSelect = onItemSelect,
            onLoadMore = onLoadMore,
            onClearFilters = onClearFilters,
            onRetryFailedSource = onRetryFailedSource
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit
): Unit {
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.explore_search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.explore_clear_search)
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboardController?.hide()
                onPerformSearch()
            }
        ),
        shape = MaterialTheme.shapes.large
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilterBar(
    uiState: ExploreViewModel.ExploreUiState,
    hasActiveFilters: Boolean,
    onOpenFilters: () -> Unit,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearFilters: () -> Unit
): Unit {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        AssistChip(
            onClick = onOpenFilters,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            label = {
                Text(
                    text = when {
                        uiState.selectedSource != null && uiState.selectedTags.isNotEmpty() ->
                            pluralStringResource(
                                R.plurals.explore_filter_source_and_genres,
                                uiState.selectedTags.size,
                                uiState.selectedSource,
                                uiState.selectedTags.size
                            )
                        uiState.selectedSource != null -> uiState.selectedSource
                        uiState.selectedTags.isNotEmpty() ->
                            pluralStringResource(
                                R.plurals.explore_filter_genres,
                                uiState.selectedTags.size,
                                uiState.selectedTags.size
                            )
                        else -> stringResource(R.string.explore_filters)
                    }
                )
            }
        )

        uiState.selectedSource?.let { source ->
            InputChip(
                selected = true,
                onClick = { onSourceSelect(null) },
                label = { Text(source) },
                modifier = Modifier.minimumInteractiveComponentSize(),
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.explore_remove_source_filter),
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }

        uiState.selectedTags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { onTagToggle(tag) },
                label = { Text(tag) },
                modifier = Modifier.minimumInteractiveComponentSize(),
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.explore_remove_tag, tag),
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }

        if (hasActiveFilters) {
            TextButton(
                onClick = onClearFilters,
                contentPadding = PaddingValues(horizontal = EasyReaderSpacing.xs)
            ) {
                Text(stringResource(R.string.common_clear))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseModeTabs(
    selected: BrowseMode,
    onSelect: (BrowseMode) -> Unit
): Unit {
    val modes = BrowseMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersBottomSheetContent(
    uiState: ExploreViewModel.ExploreUiState,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit,
    onClose: () -> Unit
): Unit {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.explore_filters),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.explore_filters_done)) }
        }

        Text(
            text = stringResource(R.string.explore_source),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
        ) {
            FilterChip(
                selected = uiState.selectedSource == null,
                onClick = { onSourceSelect(null) },
                label = { Text(stringResource(R.string.explore_all_sources)) }
            )
            uiState.sources.forEach { source ->
                FilterChip(
                    selected = uiState.selectedSource == source,
                    onClick = { onSourceSelect(if (uiState.selectedSource == source) null else source) },
                    label = { Text(source) }
                )
            }
        }

        if (uiState.availableTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.selectedTags.isNotEmpty()) {
                        stringResource(R.string.explore_genres_count, uiState.selectedTags.size)
                    } else {
                        stringResource(R.string.explore_genres)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.selectedTags.isNotEmpty()) {
                    TextButton(onClick = onClearTags) {
                        Text(stringResource(R.string.explore_clear_genres))
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                uiState.availableTags.forEach { tag ->
                    FilterChip(
                        selected = uiState.selectedTags.contains(tag),
                        onClick = { onTagToggle(tag) },
                        label = { Text(tag) }
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.explore_no_genres),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
    }
}

/** Saves the item, then reports the real outcome on Explore's own snackbar. */
private suspend fun saveExploreItem(
    context: Context,
    item: ExploreItem,
    libraryViewModel: LibraryViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenLibrary: () -> Unit
) {
    val added = libraryViewModel.addExploreItem(item)
    val result = snackbarHostState.showSnackbar(
        message = addOutcomeMessage(context, added),
        actionLabel = if (added.isFailure) null else context.getString(R.string.explore_open_library),
        duration = SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed) onOpenLibrary()
}

/**
 * Opens the item only once it is actually in the library. Returns the message to show when the add
 * failed, or null once the item is open.
 */
private suspend fun readExploreItem(
    context: Context,
    item: ExploreItem,
    alreadySaved: Boolean,
    libraryViewModel: LibraryViewModel,
    onReadItem: (ExploreItem) -> Unit
): String? {
    val added = if (alreadySaved) Result.success(false) else libraryViewModel.addExploreItem(item)
    if (added.isFailure) return addOutcomeMessage(context, added)
    onReadItem(item)
    return null
}

private fun addOutcomeMessage(context: Context, outcome: Result<Boolean>): String = outcome.fold(
    onSuccess = { added ->
        context.getString(
            if (added) R.string.explore_saved_to_library else R.string.explore_already_in_library
        )
    },
    onFailure = { e ->
        context.getString(
            R.string.explore_add_failed,
            e.message ?: context.getString(R.string.explore_add_failed_reason)
        )
    }
)
