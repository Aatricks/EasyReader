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
import io.aatricks.novelscraper.data.repository.ExploreRepository
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreRepository: ExploreRepository,
    libraryViewModel: LibraryViewModel,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var exploreItems by remember { mutableStateOf<List<ExploreItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<ExploreItem?>(null) }
    var selectedItemDetails by remember { mutableStateOf<ExploreItem?>(null) }
    var isFetchingDetails by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    var showSourceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedSource) {
        availableTags = exploreRepository.getTags(selectedSource)
        selectedTags = emptySet()
    }

    BackHandler {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
            scope.launch {
                isLoading = true
                page = 1
                exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTags.toList())
                isLoading = false
            }
        } else {
            onNavigateBack()
        }
    }

    fun fetchDetails(item: ExploreItem) {
        selectedItem = item
        selectedItemDetails = null
        isFetchingDetails = true
        scope.launch {
            val details = exploreRepository.getNovelDetails(item.url, item.source)
            selectedItemDetails = details ?: item
            isFetchingDetails = false
        }
    }

    fun loadMore() {
        if (isLoading) return
        scope.launch {
            isLoading = true
            val newItems = if (searchQuery.isBlank()) {
                exploreRepository.getPopularNovels(page + 1, selectedSource, selectedTags.toList())
            } else {
                exploreRepository.searchNovels(searchQuery, page + 1, selectedSource)
            }
            val distinctNewItems = newItems.filter { newItem -> 
                exploreItems.none { it.url == newItem.url }
            }
            
            if (distinctNewItems.isNotEmpty()) {
                exploreItems = exploreItems + distinctNewItems
                page++
            }
            isLoading = false
        }
    }

    LaunchedEffect(selectedSource, selectedTags) {
        isLoading = true
        page = 1
        exploreItems = if (isSearching && searchQuery.isNotBlank()) {
            exploreRepository.searchNovels(searchQuery, 1, selectedSource)
        } else {
            exploreRepository.getPopularNovels(1, selectedSource, selectedTags.toList())
        }
        isLoading = false
    }

    fun performSearch() {
        if (searchQuery.isBlank()) return
        scope.launch {
            isLoading = true
            page = 1
            exploreItems = exploreRepository.searchNovels(searchQuery, 1, selectedSource)
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
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
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        isSearching = false
                                        scope.launch {
                                            isLoading = true
                                            page = 1
                                            exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTags.toList())
                                            isLoading = false
                                        }
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { performSearch() })
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
                            tint = if (selectedSource != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                            scope.launch {
                                isLoading = true
                                page = 1
                                exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTags.toList())
                                isLoading = false
                            }
                        } else {
                            isSearching = true
                        }
                    }) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearching) "Close Search" else "Search"
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
            if (availableTags.isNotEmpty() && !isSearching) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedTags.isEmpty(),
                            onClick = { selectedTags = emptySet() },
                            label = { Text("All") }
                        )
                    }
                    items(availableTags) { tag ->
                        FilterChip(
                            selected = selectedTags.contains(tag),
                            onClick = {
                                selectedTags = if (selectedTags.contains(tag)) {
                                    selectedTags - tag
                                } else {
                                    selectedTags + tag
                                }
                            },
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
                    if (isLoading && exploreItems.isEmpty()) {
                        items(10) {
                            SkeletonExploreCard()
                        }
                    } else if (exploreItems.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxSize().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                                Text("No items found.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        items(exploreItems) { item ->
                            ExploreItemCard(item = item, onClick = { fetchDetails(item) })
                        }

                        if (isLoading) {
                            items(4) {
                                SkeletonExploreCard()
                            }
                        } else {
                            item {
                                LaunchedEffect(true) {
                                    loadMore()
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
                val sources = exploreRepository.getAllSources()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        ListItem(
                            headlineContent = { Text("All Sources") },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                selectedSource = null
                                showSourceDialog = false
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = if (selectedSource == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            trailingContent = {
                                RadioButton(
                                    selected = selectedSource == null,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                        )
                    }
                    items(sources) { source ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                selectedSource = source.name
                                showSourceDialog = false
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = if (selectedSource == source.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            trailingContent = {
                                RadioButton(
                                    selected = selectedSource == source.name,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (selectedItem != null) {
        ModalBottomSheet(
            onDismissRequest = {
                selectedItem = null
                selectedItemDetails = null
            },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            ExploreItemDetailSheet(
                item = selectedItemDetails ?: selectedItem!!,
                isLoading = isFetchingDetails,
                onAddToLibrary = {
                    val itemToAdd = selectedItemDetails ?: selectedItem!!
                    libraryViewModel.addExploreItem(itemToAdd, exploreRepository)
                    scope.launch {
                        snackbarHostState.showSnackbar("Adding to library...")
                    }
                    selectedItem = null
                    selectedItemDetails = null
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
