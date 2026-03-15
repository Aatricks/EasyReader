package io.aatricks.novelscraper.ui.screens.explore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.ui.viewmodel.ExploreViewModel
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
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
    
    var showSourceDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (uiState.isSearching) {
            exploreViewModel.toggleSearch()
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            ExploreTopBar(
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onSourceClick = { showSourceDialog = true },
                onToggleSearch = { exploreViewModel.toggleSearch() },
                onSearchQueryChange = { exploreViewModel.updateSearchQuery(it) },
                onPerformSearch = { exploreViewModel.performSearch() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        ExploreContent(
            uiState = uiState,
            paddingValues = paddingValues,
            onTagToggle = { exploreViewModel.toggleTag(it) },
            onClearTags = { exploreViewModel.clearTags() },
            onItemSelect = { exploreViewModel.selectItem(it) },
            onLoadMore = { exploreViewModel.loadMore() }
        )
    }

    if (showSourceDialog) {
        SourceSelectionDialog(
            uiState = uiState,
            onDismiss = { showSourceDialog = false },
            onSourceSelect = { exploreViewModel.selectSource(it) }
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
                    scope.launch { snackbarHostState.showSnackbar("Adding to library...") }
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
    uiState: ExploreViewModel.ExploreUiState,
    onNavigateBack: () -> Unit,
    onSourceClick: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit
): Unit {
    TopAppBar(
        title = {
            if (uiState.isSearching) {
                SearchTextField(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onPerformSearch = onPerformSearch,
                    onClear = onToggleSearch
                )
            } else {
                Text("Explore")
            }
        },
        actions = {
            IconButton(onClick = onSourceClick) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Select Source",
                    tint = if (uiState.selectedSource != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (uiState.isSearching) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (uiState.isSearching) "Close Search" else "Search"
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.Close, contentDescription = "Back")
            }
        }
    )
}

@Composable
private fun SearchTextField(
    query: String,
    onQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onClear: () -> Unit
): Unit {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search novels...") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(24.dp),
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onPerformSearch() })
    )
}

@Composable
private fun ExploreContent(
    uiState: ExploreViewModel.ExploreUiState,
    paddingValues: PaddingValues,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit,
    onItemSelect: (ExploreItem) -> Unit,
    onLoadMore: () -> Unit
): Unit {
    Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
        if (uiState.availableTags.isNotEmpty() && !uiState.isSearching) {
            TagRow(
                availableTags = uiState.availableTags,
                selectedTags = uiState.selectedTags,
                onTagToggle = onTagToggle,
                onClearTags = onClearTags
            )
        }

        ExploreGrid(
            uiState = uiState,
            onItemSelect = onItemSelect,
            onLoadMore = onLoadMore
        )
    }
}

@Composable
private fun TagRow(
    availableTags: List<String>,
    selectedTags: Set<String>,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit
): Unit {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
    uiState: ExploreViewModel.ExploreUiState,
    onItemSelect: (ExploreItem) -> Unit,
    onLoadMore: () -> Unit
): Unit {
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> {
                    items(10) { SkeletonExploreCard() }
                }
                uiState.items.isEmpty() -> {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        EmptyExploreState()
                    }
                }
                else -> {
                    items(uiState.items) { item ->
                        ExploreItemCard(item = item, onClick = { onItemSelect(item) })
                    }

                    if (uiState.isLoading) {
                        items(4) { SkeletonExploreCard() }
                    } else {
                        item {
                            LaunchedEffect(Unit) { onLoadMore() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyExploreState(): Unit {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 100.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("No items found.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SourceSelectionDialog(
    uiState: ExploreViewModel.ExploreUiState,
    onDismiss: () -> Unit,
    onSourceSelect: (String?) -> Unit
): Unit {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Select Source", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    SourceListItem(
                        name = "All Sources",
                        isSelected = uiState.selectedSource == null,
                        onClick = {
                            onSourceSelect(null)
                            onDismiss()
                        }
                    )
                }
                items(uiState.sources) { sourceName ->
                    SourceListItem(
                        name = sourceName,
                        isSelected = uiState.selectedSource == sourceName,
                        onClick = {
                            onSourceSelect(sourceName)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SourceListItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
): Unit {
    ListItem(
        headlineContent = { Text(name) },
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        trailingContent = {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
        }
    )
}

@Composable
fun ExploreItemCard(item: ExploreItem, onClick: () -> Unit): Unit {
    val context = LocalContext.current
    val imageRequest = remember(item.coverUrl, item.url) {
        val uri = try { java.net.URI(item.url) } catch (e: Exception) { null }
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

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            ),
                            startY = 300f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                        if (item.chapterCount > 0) {
                            Text(
                                text = "${item.chapterCount} Chapters",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray.copy(alpha = 0.8f)
                            )
                        }
                    }
                    item.author?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonExploreCard(): Unit {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun ExploreItemDetailSheet(
    item: ExploreItem,
    isLoading: Boolean = false,
    onAddToLibrary: () -> Unit,
    onRead: () -> Unit
): Unit {
    val context = LocalContext.current
    val imageRequest = remember(item.coverUrl, item.url) {
        val uri = try { java.net.URI(item.url) } catch (e: Exception) { null }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .width(100.dp)
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                item.author?.let { author ->
                    Text(
                        text = "Author: $author",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Source: ${item.source}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.chapterCount > 0) {
                    Text(
                        text = "Chapters: ${item.chapterCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRead,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Read Now",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            OutlinedButton(
                onClick = onAddToLibrary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add to Library",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Summary",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Text(
                text = item.summary.takeIf { !it.isNullOrBlank() } ?: "No summary available.",
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.5,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}
