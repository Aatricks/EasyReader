package io.aatricks.novelscraper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryItemList(
    uiState: LibraryViewModel.LibraryUiState,
    readerUiState: ReaderViewModel.ReaderUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    summaryViewModel: SummaryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    val expandedNovelState = remember { mutableStateMapOf<String, Boolean>() }
    val showFullChaptersState = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        uiState.groupedBySource.forEach { (sourceName, novels) ->
            val isSourceExpanded = !uiState.collapsedSources.contains(sourceName)

            item(key = "source_$sourceName") {
                SourceHeader(
                    name = sourceName,
                    isExpanded = isSourceExpanded,
                    onClick = { libraryViewModel.toggleSourceExpansion(sourceName) }
                )
            }

            if (isSourceExpanded) {
                novels.forEach { (groupTitle, chapterItems) ->
                    item(key = "${sourceName}_$groupTitle") {
                        val firstItem = chapterItems.firstOrNull()
                        if (firstItem?.contentType == ContentType.EPUB) {
                            EpubItemCard(
                                item = firstItem,
                                contentRepository = readerViewModel.contentRepository,
                                readerViewModel = readerViewModel,
                                libraryViewModel = libraryViewModel,
                                onCloseLibrary = onCloseLibrary
                            )
                        } else {
                            NovelGroupCard(
                                title = groupTitle,
                                items = chapterItems,
                                uiState = uiState,
                                readerUiState = readerUiState,
                                summaryUiState = summaryUiState,
                                isExpanded = expandedNovelState.getOrPut(groupTitle) { false },
                                showFullChapters = showFullChaptersState[groupTitle] ?: false,
                                onToggleExpand = {
                                    expandedNovelState[groupTitle] = !(expandedNovelState[groupTitle] ?: false)
                                },
                                onToggleShowFull = {
                                    showFullChaptersState[groupTitle] = !(showFullChaptersState[groupTitle] ?: false)
                                },
                                libraryViewModel = libraryViewModel,
                                readerViewModel = readerViewModel,
                                summaryViewModel = summaryViewModel,
                                onCloseLibrary = onCloseLibrary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceHeader(
    name: String,
    isExpanded: Boolean,
    onClick: () -> Unit
): Unit {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = EasyReaderSpacing.xs, horizontal = EasyReaderSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelGroupCard(
    title: String,
    items: List<LibraryItem>,
    uiState: LibraryViewModel.LibraryUiState,
    readerUiState: ReaderViewModel.ReaderUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    isExpanded: Boolean,
    showFullChapters: Boolean,
    onToggleExpand: () -> Unit,
    onToggleShowFull: () -> Unit,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    summaryViewModel: SummaryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    val isGroupSelected = items.all { it.id in uiState.selectedIds }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = EasyReaderSpacing.xs)
            .clip(MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = if (isGroupSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.sm)) {
            NovelGroupHeader(
                title = title,
                items = items,
                isExpanded = isExpanded,
                isSelectionMode = uiState.isSelectionMode,
                readerUiState = readerUiState,
                onToggleExpand = onToggleExpand,
                onToggleSelection = { libraryViewModel.toggleGroupSelection(title) },
                onOpenItem = { item ->
                    val loadUrl = if (item.currentChapterUrl.isNotBlank()) item.currentChapterUrl else item.url
                    readerViewModel.loadContent(loadUrl, item.id)
                    libraryViewModel.markAsCurrentlyReading(item.id)
                    onCloseLibrary()
                },
                onOpenNewChapter = { item ->
                    libraryViewModel.openNewChapter(title, item.baseNovelUrl, item.sourceName) { url, id ->
                        readerViewModel.loadContent(url, id)
                        libraryViewModel.markAsCurrentlyReading(id)
                        onCloseLibrary()
                    }
                }
            )

            if (isExpanded) {
                NovelChapterList(
                    items = items,
                    uiState = uiState,
                    summaryUiState = summaryUiState,
                    showFullChapters = showFullChapters,
                    onToggleShowFull = onToggleShowFull,
                    onChapterClick = { chapter ->
                        if (uiState.isSelectionMode) {
                            libraryViewModel.toggleSelection(chapter.id)
                        } else {
                            val loadUrl = if (chapter.currentChapterUrl.isNotBlank()) chapter.currentChapterUrl else chapter.url
                            readerViewModel.loadContent(loadUrl, chapter.id)
                            libraryViewModel.markAsCurrentlyReading(chapter.id)
                            onCloseLibrary()
                        }
                    },
                    onChapterLongClick = { libraryViewModel.toggleSelection(it.id) },
                    summaryViewModel = summaryViewModel,
                    libraryViewModel = libraryViewModel,
                    readerViewModel = readerViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelGroupHeader(
    title: String,
    items: List<LibraryItem>,
    isExpanded: Boolean,
    isSelectionMode: Boolean,
    readerUiState: ReaderViewModel.ReaderUiState,
    onToggleExpand: () -> Unit,
    onToggleSelection: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    onOpenNewChapter: (LibraryItem) -> Unit
): Unit {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) onToggleSelection()
                        else onOpenItem(items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead } ?: items.first())
                    },
                    onLongClick = onToggleSelection
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasUpdates = items.any { it.hasUpdates }
                val lastItem = items.lastOrNull()
                val isCaughtUp = lastItem?.let { it.isCurrentlyReading || it.progress > 0 } ?: false

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasUpdates && isCaughtUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (hasUpdates) {
                    Spacer(modifier = Modifier.width(EasyReaderSpacing.xs))
                    Badge(modifier = Modifier.clickable { lastItem?.let(onOpenNewChapter) }) {
                        Text("NEW", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (!isExpanded) {
                val current = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead } ?: items.first()
                Text(
                    text = current.currentChapter.ifBlank { "Chapter 1" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (current.isCurrentlyReading) {
                    Spacer(modifier = Modifier.height(EasyReaderSpacing.xxs))
                    LinearProgressIndicator(
                        progress = { readerUiState.scrollProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelChapterList(
    items: List<LibraryItem>,
    uiState: LibraryViewModel.LibraryUiState,
    summaryUiState: SummaryViewModel.SummaryUiState,
    showFullChapters: Boolean,
    onToggleShowFull: () -> Unit,
    onChapterClick: (LibraryItem) -> Unit,
    onChapterLongClick: (LibraryItem) -> Unit,
    summaryViewModel: SummaryViewModel,
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel
): Unit {
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

    val lastRead = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.lastRead }
    if (lastRead != null && lastRead.progress > 0) {
        Button(
            onClick = { onChapterClick(lastRead) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = MaterialTheme.shapes.large
        ) {
            Text("Continue: ${lastRead.currentChapter.ifBlank { "Reading" }}")
        }
        Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
    }

    val visibleChapters = if (showFullChapters) items else items.take(3)

    Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)) {
        visibleChapters.forEach { chapterItem ->
            val isSelected = uiState.selectedIds.contains(chapterItem.id)
            val isCurrent = chapterItem.id == lastRead?.id
            val chapterUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                            shape = MaterialTheme.shapes.small
                        )
                        .combinedClickable(
                            onClick = { onChapterClick(chapterItem) },
                            onLongClick = { onChapterLongClick(chapterItem) }
                        )
                        .padding(vertical = EasyReaderSpacing.xs, horizontal = EasyReaderSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapterItem.currentChapter.ifBlank { "Chapter 1" },
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else if (isCurrent) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                val cachedSummary = chapterItem.chapterSummaries[chapterUrl]
                val streamingSummary = if (summaryUiState.activeChapterUrl == chapterUrl) summaryUiState.currentSummary else cachedSummary

                ChapterSummaryDropdown(
                    chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                    chapterUrl = chapterUrl,
                    summary = streamingSummary,
                    isGenerating = summaryUiState.isGenerating && summaryUiState.activeChapterUrl == chapterUrl,
                    onGenerateSummary = {
                        scope.launch {
                            val result = readerViewModel.contentRepository.loadContent(chapterUrl)
                            if (result is ContentResult.Success) {
                                summaryViewModel.generateSummary(
                                    chapterUrl = chapterUrl,
                                    chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                                    content = result.elements.filterIsInstance<ContentElement.Text>().map { it.content }
                                ) { summary ->
                                    val updatedSummaries = chapterItem.chapterSummaries.toMutableMap()
                                    updatedSummaries[chapterUrl] = summary
                                    libraryViewModel.updateItem(chapterItem.copy(chapterSummaries = updatedSummaries))
                                }
                            }
                        }
                    },
                    onCancel = { summaryViewModel.cancelGeneration() }
                )
            }
        }

        if (items.size > 3) {
            TextButton(
                onClick = onToggleShowFull,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showFullChapters) "Show Less" else "Show All (${items.size})",
                    color = MaterialTheme.colorScheme.primary
                )
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpubItemCard(
    item: LibraryItem,
    contentRepository: ContentRepository,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onCloseLibrary: () -> Unit
): Unit {
    var epubBook by remember { mutableStateOf<EpubBook?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        epubBook = contentRepository.getEpubBook(item.url)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
    ) {
        Column(modifier = Modifier.padding(EasyReaderSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {
                                epubBook?.let { book ->
                                    val firstHref = book.spine.firstOrNull()
                                    if (firstHref != null) {
                                        readerViewModel.loadEpubChapter(item.url, firstHref, item.id)
                                        libraryViewModel.markAsCurrentlyReading(item.id)
                                        onCloseLibrary()
                                    }
                                }
                            },
                            onLongClick = {
                                libraryViewModel.removeItem(item.id)
                            }
                        )
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (epubBook != null) {
                        Text(
                            text = epubBook!!.metadata.author ?: "Unknown Author",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (isExpanded && epubBook != null) {
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(EasyReaderSpacing.xs))

                Column(verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)) {
                    epubBook!!.toc.forEach { tocItem ->
                        EpubTocItemView(
                            tocItem = tocItem,
                            epubPath = item.url,
                            itemId = item.id,
                            readerViewModel = readerViewModel,
                            libraryViewModel = libraryViewModel,
                            onCloseLibrary = onCloseLibrary,
                            depth = 0
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubTocItemView(
    tocItem: EpubTocItem,
    epubPath: String,
    itemId: String,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    onCloseLibrary: () -> Unit,
    depth: Int = 0
) {
    var isExpanded by remember { mutableStateOf(false) }
    val startPadding = when (depth) {
        0 -> 0.dp
        1 -> 16.dp
        2 -> 32.dp
        else -> 48.dp
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = {
                        readerViewModel.loadEpubChapter(epubPath, tocItem.href, itemId)
                        libraryViewModel.markAsCurrentlyReading(itemId)
                        onCloseLibrary()
                    }
                )
                .padding(start = startPadding, top = EasyReaderSpacing.xs, bottom = EasyReaderSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tocItem.hasChildren()) {
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }

            Text(
                text = tocItem.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (isExpanded && tocItem.hasChildren()) {
            Column {
                tocItem.children.forEach { child ->
                    EpubTocItemView(
                        tocItem = child,
                        epubPath = epubPath,
                        itemId = itemId,
                        readerViewModel = readerViewModel,
                        libraryViewModel = libraryViewModel,
                        onCloseLibrary = onCloseLibrary,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}
