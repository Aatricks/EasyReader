package io.aatricks.novelscraper.ui.screens.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import io.aatricks.novelscraper.data.repository.source.SmartScraperSource
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
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<ExploreItem?>(null) }
    var selectedItemDetails by remember { mutableStateOf<ExploreItem?>(null) }
    var isFetchingDetails by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }

    LaunchedEffect(selectedSource) {
        availableTags = exploreRepository.getTags(selectedSource)
        selectedTag = null // Reset tag when source changes
    }

    BackHandler {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
            scope.launch {
                isLoading = true
                page = 1
                exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTag)
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
            // Check if it's a smart scraper source
            val source = if (item.source.contains(".")) {
                SmartScraperSource(item.url.substringBefore("/", item.url.substringAfter("://").substringBefore("/"))) 
                // This is a bit hacky, better to have a way to find source by name or store it
            } else null
            
            val details = if (source != null) {
                try { source.getNovelDetails(item.url) } catch (e: Exception) { null }
            } else {
                exploreRepository.getNovelDetails(item.url, item.source)
            }
            selectedItemDetails = details ?: item
            isFetchingDetails = false
        }
    }

    fun loadMore() {
        if (isLoading) return
        scope.launch {
            isLoading = true
            val newItems = if (searchQuery.isBlank()) {
                exploreRepository.getPopularNovels(page + 1, selectedSource, selectedTag)
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

    LaunchedEffect(selectedSource, selectedTag) {
        isLoading = true
        exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTag)
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
                    IconButton(onClick = { showCustomUrlDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Custom URL")
                    }
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                            // Reload popular
                            scope.launch {
                                isLoading = true
                                page = 1
                                exploreItems = exploreRepository.getPopularNovels(1, selectedSource, selectedTag)
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
                ScrollableTabRow(
                    selectedTabIndex = if (selectedTag == null) 0 else availableTags.indexOf(selectedTag) + 1,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {},
                    indicator = {}
                ) {
                    Tab(
                        selected = selectedTag == null,
                        onClick = { selectedTag = null },
                        text = { Text("All") }
                    )
                    availableTags.forEach { tag ->
                        Tab(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = tag },
                            text = { Text(tag) }
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

    if (showCustomUrlDialog) {
        val onDiscover = {
            if (customUrl.isNotBlank()) {
                val finalUrl = if (!customUrl.startsWith("http")) "https://$customUrl" else customUrl
                showCustomUrlDialog = false
                isLoading = true
                scope.launch {
                    try {
                        val scraper = SmartScraperSource(finalUrl)
                        val items = scraper.getPopularNovels(1)
                        if (items.isNotEmpty()) {
                            exploreItems = items
                            // Save to SourceManager
                            io.aatricks.novelscraper.data.local.SourceManager(context).addSource(finalUrl)
                        } else {
                            snackbarHostState.showSnackbar("No items found at this URL.")
                        }
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Error: ${e.message}")
                    } finally {
                        isLoading = false
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showCustomUrlDialog = false },
            title = { Text("Add Custom Source") },
            text = {
                Column {
                    Text("Enter the URL of a novel or manhwa website to discover content.")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (customUrl.isNotEmpty()) {
                            {
                                IconButton(onClick = { customUrl = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onDiscover() })
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDiscover) {
                    Text("Discover")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedItem != null) {
        ExploreItemDetailDialog(
            item = selectedItemDetails ?: selectedItem!!,
            isLoading = isFetchingDetails,
            onDismiss = { 
                selectedItem = null
                selectedItemDetails = null
            },
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
fun ExploreItemDetailDialog(
    item: ExploreItem,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onAddToLibrary: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(item.coverUrl, item.url) {
        val uri = try { java.net.URI(item.url) } catch (e: Exception) { null }
        var referer = if (uri != null) "${uri.scheme}://${uri.host}/" else item.url
        
        // Special case for MangaBat/Manganato
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column {
                     AsyncImage(
                        model = imageRequest,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit
                    )
                    if (item.author != null) {
                        Text("Author: ${item.author}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Source: ${item.source}", style = MaterialTheme.typography.bodyMedium)
                    if (item.chapterCount > 0) {
                        Text("Chapters: ${item.chapterCount}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (item.summary != null && item.summary!!.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.summary!!,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                         Spacer(modifier = Modifier.height(8.dp))
                         Text(
                            text = "No summary available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAddToLibrary) {
                Text("Add to Library")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
