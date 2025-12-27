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
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.ExploreRepository
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aatricks.novelscraper.data.repository.LibraryRepository
import io.aatricks.novelscraper.data.model.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreRepository: ExploreRepository,
    libraryRepository: LibraryRepository,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var exploreItems by remember { mutableStateOf<List<ExploreItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedItem by remember { mutableStateOf<ExploreItem?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        isLoading = true
        exploreItems = exploreRepository.getPopularNovels()
        isLoading = false
    }

    fun performSearch() {
        if (searchQuery.isBlank()) return
        scope.launch {
            isLoading = true
            exploreItems = exploreRepository.searchNovels(searchQuery)
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
                                    IconButton(onClick = { searchQuery = "" }) {
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
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                            // Reload popular
                            scope.launch {
                                isLoading = true
                                exploreItems = exploreRepository.getPopularNovels()
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
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (isLoading) {
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
                            ExploreItemCard(item = item, onClick = { selectedItem = item })
                        }
                    }
                }
            }
        }
    }

    if (selectedItem != null) {
        ExploreItemDetailDialog(
            item = selectedItem!!,
            onDismiss = { selectedItem = null },
            onAddToLibrary = {
                scope.launch {
                    try {
                        libraryRepository.addItem(
                            title = selectedItem!!.title,
                            url = selectedItem!!.url,
                            contentType = ContentType.WEB // Default to WEB for scraped items
                        )
                        snackbarHostState.showSnackbar("Added to library")
                        selectedItem = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to add: ${e.message}")
                    }
                }
            }
        )
    }
}

@Composable
fun ExploreItemCard(item: ExploreItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            AsyncImage(
                model = item.coverUrl,
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
    onDismiss: () -> Unit,
    onAddToLibrary: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column {
                 AsyncImage(
                    model = item.coverUrl,
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
                if (item.summary != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
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
