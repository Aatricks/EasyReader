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
    onNavigateBack: () -> Unit
) {
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
            TopAppBar(
                title = {
                    if (uiState.isSearching) {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { exploreViewModel.updateSearchQuery(it) },
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
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { exploreViewModel.toggleSearch() }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { exploreViewModel.performSearch() })
                        )
                    } else {
                        Text("Explore")
                    }
                },
                actions = {
                    IconButton(onClick = { showSourceDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Select Source",
                            tint = if (uiState.selectedSource != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { exploreViewModel.toggleSearch() }) {
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (uiState.availableTags.isNotEmpty() && !uiState.isSearching) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedTags.isEmpty(),
                            onClick = { exploreViewModel.clearTags() },
                            label = { Text("All") }
                        )
                    }
                    items(uiState.availableTags) { tag ->
                        FilterChip(
                            selected = uiState.selectedTags.contains(tag),
                            onClick = { exploreViewModel.toggleTag(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isLoading && uiState.items.isEmpty()) {
                        items(10) { SkeletonExploreCard() }
                    } else if (uiState.items.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxSize().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                                Text("No items found.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        items(uiState.items) { item ->
                            ExploreItemCard(item = item, onClick = { exploreViewModel.selectItem(item) })
                        }

                        if (uiState.isLoading) {
                            items(4) { SkeletonExploreCard() }
                        } else {
                            item {
                                LaunchedEffect(true) {
                                    exploreViewModel.loadMore()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Select Source", style = MaterialTheme.typography.titleLarge) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        ListItem(
                            headlineContent = { Text("All Sources") },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                exploreViewModel.selectSource(null)
                                showSourceDialog = false
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = if (uiState.selectedSource == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            trailingContent = {
                                RadioButton(
                                    selected = uiState.selectedSource == null,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                        )
                    }
                    items(uiState.sources) { sourceName ->
                        ListItem(
                            headlineContent = { Text(sourceName) },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                exploreViewModel.selectSource(sourceName)
                                showSourceDialog = false
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = if (uiState.selectedSource == sourceName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            trailingContent = {
                                RadioButton(
                                    selected = uiState.selectedSource == sourceName,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) { Text("Close") }
            }
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
                    scope.launch {
                        snackbarHostState.showSnackbar("Adding to library...")
                    }
                    exploreViewModel.dismissItem()
                }
            )
        }
    }
}

@Composable
fun ExploreItemCard(item: ExploreItem, onClick: () -> Unit) {
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
                    Text(
                        text = item.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                    if (item.author != null) {
                        Text(
                            text = item.author!!,
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
fun SkeletonExploreCard() {
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
    onAddToLibrary: () -> Unit
) {
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
                if (item.author != null) {
                    Text(
                        text = "Author: ${item.author}",
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
        Button(
            onClick = onAddToLibrary,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add to Library")
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
                text = if (item.summary.isNullOrBlank()) "No summary available." else item.summary!!,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.5,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}