package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.aatricks.novelscraper.data.model.*
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.ui.ExploreRoute
import io.aatricks.novelscraper.ui.components.ChapterSummaryDropdown
import io.aatricks.novelscraper.ui.components.LibraryItemCard
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.SummaryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryDrawerContent(
    libraryViewModel: LibraryViewModel,
    readerViewModel: ReaderViewModel,
    navController: NavController,
    onOpenFilePicker: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val readerUiState by readerViewModel.uiState.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val summaryViewModel: SummaryViewModel = hiltViewModel()
    val summaryUiState by summaryViewModel.uiState.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var isAddSectionVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        summaryViewModel.initializeSummaryService()
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(onClick = {
                    onCloseDrawer()
                    navController.navigate(ExploreRoute)
                }) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Explore",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { isAddSectionVisible = !isAddSectionVisible }) {
                    Icon(
                        imageVector = if (isAddSectionVisible) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (isAddSectionVisible) "Close Add" else "Add Novel",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(visible = isAddSectionVisible) {
            Column {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Novel URL") },
                    placeholder = { Text("Enter novel URL...") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear URL"
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (urlInput.isNotBlank()) {
                            libraryViewModel.fetchAndAdd(urlInput)
                            urlInput = ""
                            isAddSectionVisible = false
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                libraryViewModel.fetchAndAdd(urlInput)
                                urlInput = ""
                                isAddSectionVisible = false
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = urlInput.isNotBlank(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onOpenFilePicker() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("Open PDF", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { libraryViewModel.updateSearchQuery(it) },
            placeholder = { Text("Search library...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { libraryViewModel.updateSearchQuery("") }) {
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

        Spacer(modifier = Modifier.height(16.dp))

        if (libraryUiState.isSelectionMode) {
             Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { libraryViewModel.removeSelectedItems() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { libraryViewModel.clearSelection() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(8.dp))

        if (libraryUiState.items.isEmpty()) {
            EmptyLibraryState()
        } else {
            val contentRepository = readerViewModel.contentRepository

            val expandedNovelState = remember { mutableStateMapOf<String, Boolean>() }
            val showFullChaptersState = remember { mutableStateMapOf<String, Boolean>() }

            val groupedBySource = libraryUiState.groupedBySource

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedBySource.forEach { sourceEntry ->
                    val sourceName = sourceEntry.key
                    val novels = sourceEntry.value
                    
                    val isSourceExpanded = !libraryUiState.collapsedSources.contains(sourceName)

                    item(key = "source_$sourceName") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { libraryViewModel.toggleSourceExpansion(sourceName) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sourceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (isSourceExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isSourceExpanded) {
                        novels.forEach { novelEntry ->
                            val groupTitle = novelEntry.key
                            val chapterItems = novelEntry.value
                            
                            item(key = groupTitle) {
                                val firstItem = chapterItems.firstOrNull()
                                if (firstItem != null && firstItem.contentType == ContentType.EPUB) {
                                    EpubItemCard(
                                        item = firstItem,
                                        contentRepository = contentRepository,
                                        readerViewModel = readerViewModel,
                                        libraryViewModel = libraryViewModel,
                                        onCloseDrawer = onCloseDrawer
                                    )
                                } else {
                                    val isExpanded = expandedNovelState.getOrPut(groupTitle) { false }
                                    val isGroupSelected = chapterItems.all { it.id in libraryUiState.selectedIds }
                                    val isSelectionMode = libraryUiState.isSelectionMode
                                    val showFullChapters = showFullChaptersState[groupTitle] ?: false

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 8.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isGroupSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(modifier = Modifier.weight(1f)) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .combinedClickable(
                                                                onClick = {
                                                                    if (isSelectionMode) {
                                                                        libraryViewModel.toggleGroupSelection(groupTitle)
                                                                    } else {
                                                                        val current = chapterItems.find { it.isCurrentlyReading }
                                                                            ?: chapterItems.maxByOrNull { it.lastRead }
                                                                            ?: chapterItems.first()
                                                                        val loadUrl = if (current.currentChapterUrl.isNotBlank()) current.currentChapterUrl else current.url
                                                                        readerViewModel.loadContent(loadUrl, current.id)
                                                                        libraryViewModel.markAsCurrentlyReading(current.id)
                                                                        onCloseDrawer()
                                                                    }
                                                                },
                                                                onLongClick = { libraryViewModel.toggleGroupSelection(groupTitle) }
                                                            )
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            val hasUpdates = chapterItems.any { it.hasUpdates }
                                                            val lastItem = chapterItems.lastOrNull()
                                                            val isCaughtUp = lastItem?.let { it.isCurrentlyReading || it.progress > 0 } ?: false
                                                            
                                                            Text(
                                                                text = groupTitle,
                                                                style = MaterialTheme.typography.titleMedium,
                                                                color = if (hasUpdates && isCaughtUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.weight(1f, fill = false)
                                                            )
                                                            
                                                            if (hasUpdates) {
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Badge(
                                                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                                                    modifier = Modifier.clickable {
                                                                        val targetItem = chapterItems.lastOrNull()
                                                                        if (targetItem != null) {
                                                                            val loadUrl = if (targetItem.currentChapterUrl.isNotBlank()) targetItem.currentChapterUrl else targetItem.url
                                                                            readerViewModel.loadContent(loadUrl, targetItem.id)
                                                                            libraryViewModel.markAsCurrentlyReading(targetItem.id)
                                                                            onCloseDrawer()
                                                                        }
                                                                    }
                                                                ) {
                                                                    Text(
                                                                        text = "NEW",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onTertiary
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        if (!isExpanded) {
                                                            val current = chapterItems.find { it.isCurrentlyReading } ?: chapterItems.maxByOrNull { it.lastRead } ?: chapterItems.first()
                                                            Text(
                                                                text = current.currentChapter.ifBlank { "Chapter 1" },
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            if (current.isCurrentlyReading) {
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                LinearProgressIndicator(
                                                                    progress = { readerUiState.scrollProgress / 100f },
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                IconButton(onClick = { expandedNovelState[groupTitle] = !isExpanded }) {
                                                    Icon(
                                                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }

                                            if (isExpanded) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                
                                                val lastRead = chapterItems.find { it.isCurrentlyReading } ?: chapterItems.maxByOrNull { it.lastRead }
                                                if (lastRead != null && lastRead.progress > 0) {
                                                    Button(
                                                        onClick = {
                                                            val loadUrl = if (lastRead.currentChapterUrl.isNotBlank()) lastRead.currentChapterUrl else lastRead.url
                                                            readerViewModel.loadContent(loadUrl, lastRead.id)
                                                            libraryViewModel.markAsCurrentlyReading(lastRead.id)
                                                            onCloseDrawer()
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                    ) {
                                                        Text("Continue: ${lastRead.currentChapter.ifBlank { "Reading" }}")
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }

                                                val visibleChapters = if (showFullChapters) chapterItems else chapterItems.take(3)
                                                
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    visibleChapters.forEach { chapterItem ->
                                                        val isSelected = libraryUiState.selectedIds.contains(chapterItem.id)
                                                        val isCurrent = chapterItem.id == lastRead?.id
                                                        
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .background(
                                                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                                                                        shape = RoundedCornerShape(4.dp)
                                                                    )
                                                                    .combinedClickable(
                                                                        onClick = {
                                                                            if (isSelectionMode) {
                                                                                libraryViewModel.toggleSelection(chapterItem.id)
                                                                            } else {
                                                                                val loadUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url
                                                                                readerViewModel.loadContent(loadUrl, chapterItem.id)
                                                                                libraryViewModel.markAsCurrentlyReading(chapterItem.id)
                                                                                onCloseDrawer()
                                                                            }
                                                                        },
                                                                        onLongClick = { libraryViewModel.toggleSelection(chapterItem.id) }
                                                                    )
                                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = chapterItem.currentChapter.ifBlank { "Chapter 1" },
                                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                                                    style = MaterialTheme.typography.bodyMedium
                                                                )
                                                            }
                                                            val chapterUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url
                                                            val cachedSummary = chapterItem.chapterSummaries.get(chapterUrl)
                                                            val streamingSummary = if (summaryUiState.activeChapterUrl == chapterUrl) summaryUiState.currentSummary else cachedSummary
                                                            
                                                            ChapterSummaryDropdown(
                                                                chapterTitle = chapterItem.currentChapter.ifBlank { chapterItem.title },
                                                                chapterUrl = chapterUrl,
                                                                summary = streamingSummary,
                                                                isGenerating = summaryUiState.isGenerating && summaryUiState.activeChapterUrl == chapterUrl,
                                                                onGenerateSummary = {
                                                                    scope.launch {
                                                                        val result = contentRepository.loadContent(chapterUrl)
                                                                        if (result is ContentRepository.ContentResult.Success) {
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
                                                    
                                                    if (chapterItems.size > 3) {
                                                        TextButton(
                                                            onClick = { showFullChaptersState[groupTitle] = !showFullChapters },
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = if (showFullChapters) "Show Less" else "Show All (${chapterItems.size})",
                                                                color = Color(0xFF4CAF50)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Empty Library",
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Library is empty",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Add your first novel using the URL field above",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
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
    onCloseDrawer: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var epubBook by remember { mutableStateOf<EpubBook?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        epubBook = contentRepository.getEpubBook(item.url)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                                        onCloseDrawer()
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
                        color = Color.White
                    )
                    if (epubBook != null) {
                        Text(
                            text = epubBook!!.metadata.author ?: "Unknown Author",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.White
                    )
                }
            }

            if (isExpanded && epubBook != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    epubBook!!.toc.forEach { tocItem ->
                        EpubTocItemView(
                            tocItem = tocItem,
                            epubPath = item.url,
                            itemId = item.id,
                            readerViewModel = readerViewModel,
                            libraryViewModel = libraryViewModel,
                            onCloseDrawer = onCloseDrawer,
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
    onCloseDrawer: () -> Unit,
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
                        onCloseDrawer()
                    }
                )
                .padding(start = startPadding, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tocItem.hasChildren()) {
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }

            Text(
                text = tocItem.title,
                color = Color.White,
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
                        onCloseDrawer = onCloseDrawer,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}