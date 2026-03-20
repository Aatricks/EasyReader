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

@Composable
private fun ExploreFilterPanel(
    uiState: ExploreViewModel.ExploreUiState,
    hasActiveFilters: Boolean,
    isCompactHeader: Boolean,
    compactSummaryState: androidx.compose.foundation.lazy.LazyListState,
    sourceRowState: androidx.compose.foundation.lazy.LazyListState,
    tagRowState: androidx.compose.foundation.lazy.LazyListState,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit,
    onClearFilters: () -> Unit
): Unit {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isCompactHeader) 0.18f else 0.25f),
        modifier = Modifier.animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            SearchField(
                query = uiState.searchQuery,
                onQueryChange = onSearchQueryChange,
                onPerformSearch = onPerformSearch
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        uiState.searchQuery.isNotBlank() && uiState.selectedSource != null ->
                            "Searching in ${uiState.selectedSource}"
                        uiState.searchQuery.isNotBlank() ->
                            "Searching all sources"
                        uiState.selectedSource != null ->
                            "Browsing ${uiState.selectedSource}"
                        else -> "Browsing popular titles"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (hasActiveFilters) {
                    TextButton(
                        onClick = onClearFilters,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("Clear all")
                    }
                }
            }

            if (isCompactHeader) {
                CompactFilterSummary(
                    selectedSource = uiState.selectedSource,
                    selectedTags = uiState.selectedTags,
                    listState = compactSummaryState,
                    onClearTags = onClearTags,
                    onSourceSelect = onSourceSelect
                )
            } else {
                ExpandedFilterControls(
                    uiState = uiState,
                    sourceRowState = sourceRowState,
                    tagRowState = tagRowState,
                    onSourceSelect = onSourceSelect,
                    onTagToggle = onTagToggle,
                    onClearTags = onClearTags
                )
            }
        }
    }
}

@Composable
private fun ExpandedFilterControls(
    uiState: ExploreViewModel.ExploreUiState,
    sourceRowState: androidx.compose.foundation.lazy.LazyListState,
    tagRowState: androidx.compose.foundation.lazy.LazyListState,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit
) {
    FilterSectionLabel("Sources")
    SourceRow(
        selectedSource = uiState.selectedSource,
        sources = uiState.sources,
        listState = sourceRowState,
        onSourceSelect = onSourceSelect
    )

    if (uiState.availableTags.isNotEmpty()) {
        if (uiState.searchQuery.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterSectionLabel("Genres")
                if (uiState.selectedTags.isNotEmpty()) {
                    TextButton(
                        onClick = onClearTags,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("Clear genres")
                    }
                }
            }
            TagRow(
                availableTags = uiState.availableTags,
                selectedTags = uiState.selectedTags,
                listState = tagRowState,
                onTagToggle = onTagToggle,
                onClearTags = onClearTags
            )
        } else if (uiState.selectedTags.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                ) {
                    Text(
                        text = "Saved genre filters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.selectedTags.joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFilterSummary(
    selectedSource: String?,
    selectedTags: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearTags: () -> Unit,
    onSourceSelect: (String?) -> Unit
) {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSourceSelect(null) },
                label = { Text("All sources") }
            )
        }
        selectedSource?.let { source ->
            item {
                FilterChip(
                    selected = true,
                    onClick = { onSourceSelect(null) },
                    label = { Text(source) }
                )
            }
        }
        if (selectedTags.isNotEmpty()) {
            item {
                FilterChip(
                    selected = true,
                    onClick = onClearTags,
                    label = { Text("${selectedTags.size} genres") }
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit
): Unit {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search titles or series") },
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
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onPerformSearch() }),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun FilterSectionLabel(text: String): Unit {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SourceRow(
    selectedSource: String?,
    sources: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSourceSelect: (String?) -> Unit
): Unit {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSourceSelect(null) },
                label = { Text("All sources") }
            )
        }
        items(sources) { sourceName ->
            FilterChip(
                selected = selectedSource == sourceName,
                onClick = {
                    onSourceSelect(if (selectedSource == sourceName) null else sourceName)
                },
                label = { Text(sourceName) }
            )
        }
    }
}

@Composable
private fun TagRow(
    availableTags: List<String>,
    selectedTags: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit
): Unit {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedTags.isEmpty(),
                onClick = onClearTags,
                label = { Text("All") }
            )
        }
        items(availableTags) { tag ->
            FilterChip(
                selected = selectedTags.contains(tag),
                onClick = { onTagToggle(tag) },
                label = { Text(tag) }
            )
        }
    }
}

@Composable
private fun ExploreGrid(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
    uiState: ExploreViewModel.ExploreUiState,
    hasActiveFilters: Boolean,
    onItemSelect: (ExploreItem) -> Unit,
    onLoadMore: () -> Unit,
    onClearFilters: () -> Unit
): Unit {
    Box(modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(bottom = EasyReaderSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
        ) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SkeletonFeaturedExploreCard()
                    }
                    items(6) { SkeletonExploreCard() }
                }

                uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyExploreState(
                            query = uiState.searchQuery,
                            hasActiveFilters = hasActiveFilters,
                            onClearFilters = onClearFilters
                        )
                    }
                }

                else -> {
                    val featuredItem = uiState.items.first()
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FeaturedExploreCard(
                            item = featuredItem,
                            onClick = { onItemSelect(featuredItem) }
                        )
                    }

                    items(uiState.items.drop(1), key = { it.url }) { item ->
                        ExploreItemCard(
                            item = item,
                            onClick = { onItemSelect(item) }
                        )
                    }

                    if (uiState.isLoading) {
                        items(4) { SkeletonExploreCard() }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(1.dp)) }
                    }
                }
            }
        }

        LaunchedEffect(gridState, uiState.items.size, uiState.isLoading, uiState.canLoadMore) {
            snapshotFlow {
                val layoutInfo = gridState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) {
                    false
                } else {
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val shouldLoadMore = uiState.canLoadMore && !uiState.isLoading && uiState.items.isNotEmpty() && lastVisible >= totalItems - 4
                    shouldLoadMore
                }
            }
                .distinctUntilChanged()
                .filter { it }
                .collectLatest { onLoadMore() }
        }
    }
}

@Composable
private fun EmptyExploreState(
    query: String,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit
): Unit {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EasyReaderSpacing.xxl),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = EasyReaderSpacing.lg, vertical = EasyReaderSpacing.xl),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
        ) {
            Text(
                text = if (query.isNotBlank()) "No matches for \"$query\"" else "Nothing to show yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (hasActiveFilters) {
                    "Try another source or clear your filters to broaden the results."
                } else {
                    "Pull results from another source or try a different search."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasActiveFilters) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                OutlinedButton(onClick = onClearFilters) {
                    Text("Clear filters")
                }
            }
        }
    }
}

@Composable
private fun FeaturedExploreCard(
    item: ExploreItem,
    onClick: () -> Unit
): Unit {
    val imageRequest = rememberExploreImageRequest(item)

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(EasyReaderSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Text(
                        text = "Popular on ${item.source}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = supportingLine(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    MetaPill(text = item.source)
                    if (item.chapterCount > 0) {
                        MetaPill(text = "${item.chapterCount} ch")
                    }
                }
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .width(128.dp)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun ExploreItemCard(
    item: ExploreItem,
    onClick: () -> Unit
): Unit {
    val imageRequest = rememberExploreImageRequest(item)

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = EasyReaderSpacing.lg, topEnd = EasyReaderSpacing.lg)),
                    contentScale = ContentScale.Crop
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(EasyReaderSpacing.xs),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = item.source,
                        modifier = Modifier.padding(horizontal = EasyReaderSpacing.xs, vertical = EasyReaderSpacing.xxs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = supportingLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetaPill(text: String): Unit {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = EasyReaderSpacing.xs, vertical = EasyReaderSpacing.xxs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SkeletonExploreCard(): Unit {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.58f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
            )
            Column(
                modifier = Modifier.padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                )
            }
        }
    }
}

@Composable
private fun SkeletonFeaturedExploreCard(): Unit {
    val infiniteTransition = rememberInfiniteTransition(label = "featured_skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.58f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "featured_alpha"
    )

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(EasyReaderSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.lg)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Box(
                        modifier = Modifier
                            .width(92.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(128.dp)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun ExploreItemDetailSheet(
    item: ExploreItem,
    isLoading: Boolean = false,
    onAddToLibrary: () -> Unit,
    onRead: () -> Unit
): Unit {
    val imageRequest = rememberExploreImageRequest(item)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EasyReaderSpacing.lg, vertical = EasyReaderSpacing.sm)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.md)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .width(112.dp)
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                MetaPill(text = item.source)
                item.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.chapterCount > 0) {
                    Text(
                        text = "${item.chapterCount} chapters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item.rating?.let { rating ->
                    Text(
                        text = "Rating $rating",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item.rank?.let { rank ->
                    Text(
                        text = "Rank $rank",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            Button(
                onClick = onRead,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                Text("Read")
            }

            OutlinedButton(
                onClick = onAddToLibrary,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                Text("Save")
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.sm))
                    Text(
                        text = "Loading details...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = item.summary.takeIf { !it.isNullOrBlank() } ?: "Summary not available for this title yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35
                )
            }
        }

        Spacer(modifier = Modifier.height(EasyReaderSpacing.lg))
    }
}

@Composable
private fun rememberExploreImageRequest(item: ExploreItem): ImageRequest {
    val context = LocalContext.current

    return remember(item.coverUrl, item.url, item.source) {
        val uri = try {
            java.net.URI(item.url)
        } catch (_: Exception) {
            null
        }

        var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else item.url
        if (item.source == "MangaBat" || referer.contains("mangabat") || referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(item.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
            .crossfade(true)
            .build()
    }
}

private fun supportingLine(item: ExploreItem): String {
    return when {
        item.chapterCount > 0 -> "${item.chapterCount} chapters"
        !item.author.isNullOrBlank() -> item.author
        !item.rating.isNullOrBlank() -> "Rating ${item.rating}"
        else -> "Open details"
    }
}
