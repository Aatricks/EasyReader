package io.aatricks.novelscraper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.ui.ExploreRoute
import io.aatricks.novelscraper.ui.components.ChapterSummaryDropdown
import io.aatricks.novelscraper.ui.theme.EasyReaderMotion
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.SummaryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    onNavigateBack: () -> Unit
): Unit {
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val readerUiState by readerViewModel.uiState.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val summaryViewModel: SummaryViewModel = hiltViewModel()
    val summaryUiState by summaryViewModel.uiState.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var isAddSectionVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        summaryViewModel.initializeSummaryService()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { navController.navigate(ExploreRoute) }) {
                        Text("Discover")
                    }
                    FilledTonalButton(
                        onClick = { isAddSectionVisible = !isAddSectionVisible }
                    ) {
                        Text(if (isAddSectionVisible) "Hide tools" else "Add / import")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(paddingValues)
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.md)
        ) {
            AnimatedVisibility(
                visible = isAddSectionVisible,
                enter = expandVertically(animationSpec = tween(EasyReaderMotion.medium)) + fadeIn(animationSpec = tween(EasyReaderMotion.short)),
                exit = shrinkVertically(animationSpec = tween(EasyReaderMotion.short)) + fadeOut(animationSpec = tween(EasyReaderMotion.short))
            ) {
                AddNovelSection(
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    onAddClick = {
                        libraryViewModel.fetchAndAdd(urlInput)
                        urlInput = ""
                        isAddSectionVisible = false
                    },
                    onAiSetupClick = {
                        libraryViewModel.beginAiSetup(urlInput)
                    },
                    onOpenPdfClick = {
                        onNavigateBack()
                        onOpenFilePicker()
                    }
                )
            }

            if (isAddSectionVisible) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
            }

            SearchLibraryField(
                query = searchQuery,
                onQueryChange = { libraryViewModel.updateSearchQuery(it) }
            )

            if (libraryUiState.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                LibraryStatusRow(
                    query = searchQuery,
                    totalCount = libraryUiState.items.size,
                    visibleCount = libraryUiState.filteredItems.size,
                    isSelectionMode = libraryUiState.isSelectionMode,
                    selectedCount = libraryUiState.selectedCount,
                    onSelectionClick = {
                        if (libraryUiState.isSelectionMode) {
                            libraryViewModel.clearSelection()
                        } else {
                            libraryViewModel.enterSelectionMode()
                        }
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.md))
            }

            if (libraryUiState.isSelectionMode) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                SelectionActions(
                    onDelete = { libraryViewModel.removeSelectedItems() },
                    onCancel = { libraryViewModel.clearSelection() }
                )
            }

            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

            if (libraryUiState.items.isEmpty()) {
                EmptyLibraryState()
            } else {
                LibraryItemList(
                    uiState = libraryUiState,
                    readerUiState = readerUiState,
                    summaryUiState = summaryUiState,
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel,
                    summaryViewModel = summaryViewModel,
                    onCloseLibrary = onNavigateBack
                )
            }
        }
    }

    libraryUiState.aiSetupPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { libraryViewModel.dismissAiSetupPreview() },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryViewModel.confirmAiSetup()
                        urlInput = ""
                        isAddSectionVisible = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { libraryViewModel.dismissAiSetupPreview() }) { Text("Cancel") }
            },
            title = { Text("Confirm AI Setup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
                    Text("Source: ${preview.displayName}")
                    Text("Title: ${preview.title}")
                    Text("Type: ${preview.contentKind.name.replace('_', ' ')}")
                    Text("Base URL: ${preview.baseNovelUrl}")
                    Text("First chapter: ${preview.firstChapterTitle}")
                    Text("Detected chapters: ${preview.chapterCount}")
                }
            }
        )
    }

    libraryUiState.aiSetupFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { libraryViewModel.dismissAiSetupFailure() },
            confirmButton = {
                TextButton(onClick = { libraryViewModel.addFallbackFromAiSetupFailure() }) {
                    Text("Add Generic")
                }
            },
            dismissButton = {
                TextButton(onClick = { libraryViewModel.dismissAiSetupFailure() }) { Text("Close") }
            },
            title = { Text("AI Setup Failed") },
            text = { Text(failure) }
        )
    }
}

@Composable
private fun AddNovelSection(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onAiSetupClick: () -> Unit,
    onOpenPdfClick: () -> Unit
): Unit {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.md)) {
            Text(
                text = "Add from the web or import a file",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
            Text(
                text = "Paste a novel URL to add it now, or import a file from your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text("Novel URL") },
                placeholder = { Text("Paste a novel URL") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (urlInput.isNotEmpty()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear URL")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (urlInput.isNotBlank()) onAddClick() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
            ) {
                Button(
                    onClick = onAddClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    enabled = urlInput.isNotBlank(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Text("Add from URL", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onAiSetupClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = urlInput.startsWith("http://") || urlInput.startsWith("https://"),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("AI Setup", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onOpenPdfClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("Import file", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SearchLibraryField(
    query: String,
    onQueryChange: (String) -> Unit
): Unit {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search your library") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        singleLine = true
    )
}

@Composable
private fun LibraryStatusRow(
    query: String,
    totalCount: Int,
    visibleCount: Int,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSelectionClick: () -> Unit
): Unit {
    val statusText = when {
        isSelectionMode && selectedCount > 0 -> "$selectedCount selected"
        isSelectionMode -> "Select titles to remove them"
        query.isNotBlank() -> "${formatLibraryCount(visibleCount, "result")} in view"
        else -> formatLibraryCount(totalCount, "title")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onSelectionClick) {
            Text(if (isSelectionMode) "Done" else "Select")
        }
    }
}

@Composable
private fun SelectionActions(
    onDelete: () -> Unit,
    onCancel: () -> Unit
): Unit {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = EasyReaderSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        Button(
            onClick = onDelete,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            Text("Delete selected", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
            Text("Done selecting", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyLibraryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm),
                modifier = Modifier.padding(EasyReaderSpacing.xl)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Empty Library",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = "Your library is empty",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Add a title from Explore or import a file to start building your shelf.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun formatLibraryCount(count: Int, noun: String): String {
    val suffix = if (count == 1) noun else "${noun}s"
    return "$count $suffix"
}
