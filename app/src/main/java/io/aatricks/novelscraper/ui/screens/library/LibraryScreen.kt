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
                    IconButton(onClick = { navController.navigate(ExploreRoute) }) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Explore"
                        )
                    }
                    IconButton(onClick = { isAddSectionVisible = !isAddSectionVisible }) {
                        Icon(
                            imageVector = if (isAddSectionVisible) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (isAddSectionVisible) "Close add" else "Add item"
                        )
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
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm)
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
                    onOpenPdfClick = {
                        onNavigateBack()
                        onOpenFilePicker()
                    }
                )
            }

            SearchLibraryField(
                query = searchQuery,
                onQueryChange = { libraryViewModel.updateSearchQuery(it) }
            )

            Spacer(modifier = Modifier.height(EasyReaderSpacing.md))

            if (libraryUiState.isSelectionMode) {
                SelectionActions(
                    onDelete = { libraryViewModel.removeSelectedItems() },
                    onCancel = { libraryViewModel.clearSelection() }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
}

@Composable
private fun AddNovelSection(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onOpenPdfClick: () -> Unit
): Unit {
    Column {
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            label = { Text("Novel URL") },
            placeholder = { Text("Enter novel URL...") },
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
                Text("Add", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onOpenPdfClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Import File", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.sm))
    }
}

@Composable
private fun SearchLibraryField(
    query: String,
    onQueryChange: (String) -> Unit
): Unit {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search library...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        ),
        singleLine = true
    )
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
            Text("Delete", fontWeight = FontWeight.SemiBold)
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
            Text("Cancel", fontWeight = FontWeight.SemiBold)
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

