package io.aatricks.novelscraper.ui.screens.explore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.ExploreViewModel
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreViewModel: ExploreViewModel,
    libraryViewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    onReadItem: (ExploreItem) -> Unit
): Unit {
    val uiState by exploreViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val hasActiveFilters = remember(uiState.searchQuery, uiState.selectedSource, uiState.selectedTags) {
        uiState.searchQuery.isNotBlank() || uiState.selectedSource != null || uiState.selectedTags.isNotEmpty()
    }

    BackHandler {
        if (hasActiveFilters) {
            exploreViewModel.clearFilters()
        } else {
            onNavigateBack()
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
            onSourceSelect = { exploreViewModel.selectSource(it) },
            onTagToggle = { exploreViewModel.toggleTag(it) },
            onClearTags = { exploreViewModel.clearTags() },
            onClearFilters = { exploreViewModel.clearFilters() },
            onItemSelect = { exploreViewModel.selectItem(it) },
            onLoadMore = { exploreViewModel.loadMore() }
        )
    }

    if (uiState.selectedItem != null) {
        ModalBottomSheet(
            onDismissRequest = { exploreViewModel.dismissItem() },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            ExploreItemDetailSheet(
                item = uiState.selectedItemDetails ?: uiState.selectedItem!!,
                isLoading = uiState.isFetchingDetails,
                onAddToLibrary = {
                    val itemToAdd = uiState.selectedItemDetails ?: uiState.selectedItem!!
                    libraryViewModel.addExploreItem(itemToAdd, exploreViewModel.exploreRepository)
                    scope.launch { snackbarHostState.showSnackbar("Saved to library") }
                    exploreViewModel.dismissItem()
                },
                onRead = {
                    val itemToRead = uiState.selectedItemDetails ?: uiState.selectedItem!!
                    libraryViewModel.addExploreItem(itemToRead, exploreViewModel.exploreRepository)
                    onReadItem(itemToRead)
                    exploreViewModel.dismissItem()
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
        title = { Text("Explore") },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            if (hasActiveFilters) {
                TextButton(onClick = onClearFilters) {
                    Text("Reset")
                }
            }
        }
    )
}

@Composable
private fun ExploreContent(
    uiState: ExploreViewModel.ExploreUiState,
    modifier: Modifier = Modifier,
    hasActiveFilters: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit,
    onClearFilters: () -> Unit,
    onItemSelect: (ExploreItem) -> Unit,
    onLoadMore: () -> Unit
): Unit {
    val gridState = rememberLazyGridState()
    val compactSummaryState = rememberLazyListState()
    val sourceRowState = rememberLazyListState()
    val tagRowState = rememberLazyListState()
    var isCompactHeader by remember { mutableStateOf(false) }

    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) {
        val shouldCompact = gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 140
        val shouldExpand = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 48

        when {
            shouldCompact && !isCompactHeader -> isCompactHeader = true
            shouldExpand && isCompactHeader -> isCompactHeader = false
        }
    }

    Column(modifier = modifier.padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)) {
        ExploreFilterPanel(
            uiState = uiState,
            hasActiveFilters = hasActiveFilters,
            isCompactHeader = isCompactHeader,
            compactSummaryState = compactSummaryState,
            sourceRowState = sourceRowState,
            tagRowState = tagRowState,
            onSearchQueryChange = onSearchQueryChange,
            onPerformSearch = onPerformSearch,
            onSourceSelect = onSourceSelect,
            onTagToggle = onTagToggle,
            onClearTags = onClearTags,
            onClearFilters = onClearFilters
        )

        Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

        ExploreGrid(
            gridState = gridState,
            modifier = Modifier.weight(1f),
            uiState = uiState,
            hasActiveFilters = hasActiveFilters,
            onItemSelect = onItemSelect,
            onLoadMore = onLoadMore,
            onClearFilters = onClearFilters
        )
    }
}

