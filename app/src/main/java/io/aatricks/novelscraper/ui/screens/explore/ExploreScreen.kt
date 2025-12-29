package io.aatricks.novelscraper.ui.screens.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.ui.components.CloudflareBypassDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreViewModel: io.aatricks.novelscraper.ui.viewmodel.ExploreViewModel,
    exploreRepository: ExploreRepository,
    libraryViewModel: io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by exploreViewModel.uiState.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<ExploreItem?>(null) }
    var selectedItemDetails by remember { mutableStateOf<ExploreItem?>(null) }
    var isFetchingDetails by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    fun fetchDetails(item: ExploreItem) {
        selectedItem = item
        selectedItemDetails = null
        isFetchingDetails = true
        scope.launch {
            try {
                val details = exploreRepository.getNovelDetails(item.url, item.source)
                selectedItemDetails = details ?: item
            } catch (e: Exception) {
                if (e.message?.contains("Cloudflare", ignoreCase = true) == true) {
                    exploreViewModel.triggerCloudflareChallenge(item.url)
                } else {
                    selectedItemDetails = item
                }
            } finally {
                isFetchingDetails = false
            }
        }
    }

    fun loadMore() {
        if (uiState.isLoading) return
        if (uiState.searchQuery.isBlank()) {
            exploreViewModel.loadPopularNovels(uiState.page + 1)
        } else {
            exploreViewModel.searchNovels(uiState.searchQuery, uiState.page + 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { exploreViewModel.searchNovels(it, 1) },
                            placeholder = { Text("Search novels...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor =  MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        exploreViewModel.loadPopularNovels(1)
                                        isSearching = false
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { exploreViewModel.searchNovels(uiState.searchQuery, 1) })
                        )
                    } else {
                        Text("Explore")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            exploreViewModel.loadPopularNovels(1)
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
            if (uiState.isLoading && uiState.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                if (uiState.items.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No items found.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { exploreViewModel.loadPopularNovels(1) }) {
                            Text("Retry / Refresh")
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.items) { item ->
                            ExploreItemCard(item = item, onClick = { fetchDetails(item) })
                        }

                        item {
                            LaunchedEffect(true) {
                                loadMore()
                            }
                            if (uiState.isLoading) {
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

    uiState.cloudflareChallengeUrl?.let { url ->
        val preferencesManager = remember { PreferencesManager(context) }
        CloudflareBypassDialog(
            url = url,
            onDismiss = { exploreViewModel.onCloudflareBypassed() },
            onBypassed = { exploreViewModel.onCloudflareBypassed() },
            preferencesManager = preferencesManager
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
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onAddToLibrary: () -> Unit
) {
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