package io.aatricks.novelscraper.ui.screens.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.ExploreRepository
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.data.model.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreRepository: ExploreRepository,
    libraryViewModel: io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel,
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
        selectedTags = emptySet() // Reset tags when source changes
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
            // Check if new items are already present to avoid infinite loops with sources that redirect to page 1
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
        exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTags.toList())
        page = 1
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
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor =  MaterialTheme.colorScheme.primary, // Transparent or matching color
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        // Reset to popular when clearing
                                        isSearching = false
                                        scope.launch {
                                            isLoading = true
                                            page = 1
                                            exploreItems = exploreRepository.getPopularNovels(1)
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
                            // Reload popular
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
                if (isLoading && exploreItems.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    if (exploreItems.isEmpty()) {
                        Text("No items found.", modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(exploreItems) { item ->
                                ExploreItemCard(item = item, onClick = { fetchDetails(item) })
                            }

                            item {
                                LaunchedEffect(true) {
                                    loadMore()
                                }
                                if (isLoading) {
                                    Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
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
            title = { Text("Select Source") },
            text = {
                val sources = exploreRepository.getAllSources()
                LazyColumn {
                    item {
                        ListItem(
                            headlineContent = { Text("All Sources") },
                            modifier = Modifier.clickable {
                                selectedSource = null
                                showSourceDialog = false
                            },
                            trailingContent = {
                                if (selectedSource == null) {
                                    RadioButton(selected = true, onClick = null)
                                }
                            }
                        )
                    }
                    items(sources) { source ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            modifier = Modifier.clickable {
                                selectedSource = source.name
                                showSourceDialog = false
                            },
                            trailingContent = {
                                if (selectedSource == source.name) {
                                    RadioButton(selected = true, onClick = null)
                                }
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
        
        // Special case for MangaBat/Manganato - images often require this referer
        // Special case for MangaBat/Manganato - images often require this referer
        if (item.source == "MangaBat" || referer.contains("mangabat") || referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(item.coverUrl)
            .addHeader("Referer", referer)
            .crossfade(true)
            .build()
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .weight(0.3f)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.author != null) {
                    Text(
                        text = item.author,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
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
        
        // Special case for MangaBat/Manganato
        if (item.source == "MangaBat" || referer.contains("mangabat") || referer.contains("manganato")) {
            referer = "https://manganato.com/"
        }

        ImageRequest.Builder(context)
            .data(item.coverUrl)
            .addHeader("Referer", referer)
            .crossfade(true)
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
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
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
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
