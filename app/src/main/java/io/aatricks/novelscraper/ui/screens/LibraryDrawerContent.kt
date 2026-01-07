package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.EpubBook
import io.aatricks.novelscraper.data.model.EpubTocItem
import io.aatricks.novelscraper.data.repository.ContentRepository
import io.aatricks.novelscraper.ui.components.LibraryItemCard
import io.aatricks.novelscraper.ui.components.ChapterSummaryDropdown
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel
import io.aatricks.novelscraper.ui.viewmodel.SummaryViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Library drawer content displaying the novel collection and management controls.
 *
 * Features:
 * - URL input field for adding new novels
 * - Add (+) and download (↓) action buttons
 * - Delete selected button (shown only in selection mode)
 * - Grouped library items with section headers
 * - Progress tracking for each novel
 * - Selection mode for bulk operations
 * - Current reading indicator
 *
 * @param libraryViewModel ViewModel managing library state
 * @param readerViewModel ViewModel managing reader state
 * @param onOpenFilePicker Callback to open file picker
 * @param onCloseDrawer Callback to close the drawer
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryDrawerContent(
    libraryViewModel: io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel,
    readerViewModel: io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel,
    onOpenFilePicker: () -> Unit,
    onCloseDrawer: () -> Unit,
    onExploreClick: () -> Unit = {}
) {
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val readerUiState by readerViewModel.uiState.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val summaryViewModel: SummaryViewModel = viewModel()
    val summaryUiState by summaryViewModel.uiState.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var isAddSectionVisible by remember { mutableStateOf(false) }

    // Initialize summary service on first composition
    LaunchedEffect(Unit) {
        summaryViewModel.initializeSummaryService()
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row {
                IconButton(onClick = onExploreClick) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Explore",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { isAddSectionVisible = !isAddSectionVisible }) {
                    Icon(
                        imageVector = if (isAddSectionVisible) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (isAddSectionVisible) "Close Add" else "Add Novel",
                        tint = Color.White
                    )
                }
            }
        }

        // Add Novel Section
        androidx.compose.animation.AnimatedVisibility(visible = isAddSectionVisible) {
            Column {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Novel URL", color = Color.Gray) },
                    placeholder = { Text("Enter novel URL...", color = Color.DarkGray) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear URL",
                                    tint = Color.Gray
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
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xFF4CAF50)
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
                            containerColor = Color(0xFF4CAF50),
                            disabledContainerColor = Color.DarkGray
                        ),
                        enabled = urlInput.isNotBlank(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onOpenFilePicker() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF795548)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("Open PDF", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Library Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { libraryViewModel.updateSearchQuery(it) },
            placeholder = { Text("Search library...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { libraryViewModel.updateSearchQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Gray,
                unfocusedBorderColor = Color.DarkGray,
                cursorColor = Color(0xFF4CAF50)
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
                    onClick = { libraryViewModel.exitSelectionMode() },
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

        // Library Items List
        if (libraryUiState.items.isEmpty()) {
            EmptyLibraryState()
        } else {
            val context = LocalContext.current
            val contentRepository = remember { ContentRepository(context) }
            val scope = rememberCoroutineScope()

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
                                color = Color(0xFF4CAF50),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (isSourceExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    }

                    if (isSourceExpanded) {
                        novels.forEach { novelEntry ->
                            val groupTitle = novelEntry.key
                            val items = novelEntry.value
                            
                            item(key = groupTitle) {
                                val firstItem = items.firstOrNull()
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
                                    val isGroupSelected = libraryViewModel.isGroupSelected(groupTitle)
                                    val isSelectionMode = libraryUiState.isSelectionMode
                                    val showFullChapters = showFullChaptersState[groupTitle] ?: false

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 8.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isGroupSelected) Color(0xFF1E3A8A) else Color(0xFF0D0D0D)
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
                                                                        val current = items.find { it.isCurrentlyReading }
                                                                            ?: items.maxByOrNull { it.progress }
                                                                            ?: items.first()
                                                                        val loadUrl = if (current.currentChapterUrl.isNotBlank()) current.currentChapterUrl else current.url
                                                                        readerViewModel.loadContent(loadUrl, current.id)
                                                                        libraryViewModel.markAsCurrentlyReading(current.id)
                                                                        onCloseDrawer()
                                                                    }
                                                                },
                                                                onLongClick = { libraryViewModel.toggleGroupSelection(groupTitle) }
                                                            )
                                                    ) {
                                                        Text(
                                                            text = groupTitle,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            color = Color.White
                                                        )
                                                        if (!isExpanded) {
                                                            val current = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.progress } ?: items.first()
                                                            Text(
                                                                text = current.currentChapter.ifBlank { "Chapter 1" },
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = Color.Gray
                                                            )
                                                            if (current.isCurrentlyReading) {
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                LinearProgressIndicator(
                                                                    progress = { readerUiState.scrollProgress / 100f },
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    color = Color(0xFF4CAF50),
                                                                    trackColor = Color(0xFF2C2C2C)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                IconButton(onClick = { expandedNovelState[groupTitle] = !isExpanded }) {
                                                    Icon(
                                                        imageVector = if (isExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
                                                        contentDescription = null,
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            if (isExpanded) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                
                                                // Continue Reading Button
                                                val lastRead = items.find { it.isCurrentlyReading } ?: items.maxByOrNull { it.progress }
                                                if (lastRead != null && lastRead.progress > 0) {
                                                    Button(
                                                        onClick = {
                                                            val loadUrl = if (lastRead.currentChapterUrl.isNotBlank()) lastRead.currentChapterUrl else lastRead.url
                                                            readerViewModel.loadContent(loadUrl, lastRead.id)
                                                            libraryViewModel.markAsCurrentlyReading(lastRead.id)
                                                            onCloseDrawer()
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                                    ) {
                                                        Text("Continue: ${lastRead.currentChapter.ifBlank { "Reading" }}")
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }

                                                val visibleChapters = if (showFullChapters) items else items.take(3)
                                                
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    visibleChapters.forEach { chapterItem ->
                                                        val isSelected = libraryUiState.selectedIds.contains(chapterItem.id)
                                                        val isCurrent = chapterItem.id == lastRead?.id
                                                        
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .background(
                                                                        if (isSelected) Color(0xFF1E3A8A).copy(alpha = 0.5f) else Color.Transparent,
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
                                                                    color = if (isSelected) Color(0xFF90CAF9) else if (isCurrent) Color(0xFF4CAF50) else Color.White,
                                                                    style = MaterialTheme.typography.bodyMedium
                                                                )
                                                            }
                                                            // Summary Dropdown logic...
                                                            val chapterUrl = if (chapterItem.currentChapterUrl.isNotBlank()) chapterItem.currentChapterUrl else chapterItem.url
                                                            val cachedSummary = chapterItem.chapterSummaries?.get(chapterUrl)
                                                            val streamingSummary = if (summaryUiState.activeChapterUrl == chapterUrl) summaryUiState.currentSummary else cachedSummary
                                                            if (isSelected || isCurrent) { // Only show summary option for interesting items to save space? No, keep it.
                                                                // Actually keeping it for all items as requested originally
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
                                                                                    content = result.elements.filterIsInstance<io.aatricks.novelscraper.data.model.ContentElement.Text>().map { it.content }
                                                                                ) { summary ->
                                                                                    libraryViewModel.updateChapterSummary(chapterItem.id, chapterUrl, summary)
                                                                                }
                                                                            }
                                                                        }
                                                                    },
                                                                    onCancel = { summaryViewModel.cancelGeneration() }
                                                                )
                                                            }
                                                        }
                                                    }
                                                    
                                                    if (items.size > 3) {
                                                        TextButton(
                                                            onClick = { showFullChaptersState[groupTitle] = !showFullChapters },
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = if (showFullChapters) "Show Less" else "Show All (${items.size})",
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

/**
 * Try to extract a chapter label from a URL or title string.
 * Returns a string like "Chapter 12" or null if not found.
 */
private fun extractChapterLabelFromUrl(text: String): String? {
    // common patterns: "chapter 12", "ch12", "/12", "-12"
    val patterns = listOf(
        Regex("chapter\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("ch(?:apter)?\\D*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("/(\\d+)(?:/|$)"),
        Regex("-(\\d+)(?:\\D|$)")
    )

    for (r in patterns) {
        val m = r.find(text)
        if (m != null && m.groupValues.size >= 2) {
            val num = m.groupValues[1]
            return "Chapter $num"
        }
    }
    return null
}

/**
 * Try to extract a chapter label from a title string.
 * Returns a string like "Chapter 12" or null if not found.
 */
private fun extractChapterLabelFromTitle(title: String?): String? {
    if (title == null) return null
    val regex = Regex("(chapter|ch|ch\\.)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    val match = regex.find(title)
    return match?.let { "Chapter ${it.groupValues[2]}" }
}

/**
 * Display library items grouped by reading status
 */
@Composable
private fun LibraryItemsList(
    items: List<LibraryItem>,
    selectedItems: Set<String>,
    currentItemId: String?,
    onItemClick: (LibraryItem) -> Unit,
    onItemLongClick: (LibraryItem) -> Unit
) {
    val currentItem = items.find { it.id == currentItemId }
    val otherItems = items.filter { it.id != currentItemId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Current Reading Section
        if (currentItem != null) {
            item {
                SectionHeader(title = "Currently Reading")
            }
            item {
                LibraryItemCard(
                    item = currentItem,
                    isSelected = selectedItems.contains(currentItem.id),
                    isCurrent = true,
                    onClick = { onItemClick(currentItem) },
                    onLongClick = { onItemLongClick(currentItem) }
                )
            }

            if (otherItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Other Novels Section
        if (otherItems.isNotEmpty()) {
            item {
                SectionHeader(title = "Library")
            }
            items(otherItems, key = { it.id }) { item ->
                LibraryItemCard(
                    item = item,
                    isSelected = selectedItems.contains(item.id),
                    isCurrent = false,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) }
                )
            }
        }
    }
}

/**
 * Section header for grouping library items
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.Gray,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * Empty state when no library items exist
 */
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

/**
 * Render EPUB item with hierarchical TOC
 */
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

    // Load EPUB TOC
    LaunchedEffect(item.url) {
        scope.launch {
            android.util.Log.d("LibraryDrawer", "Loading EPUB from: ${item.url}")
            epubBook = contentRepository.getEpubBook(item.url)
            android.util.Log.d("LibraryDrawer", "EPUB loaded: ${epubBook != null}, TOC size: ${epubBook?.toc?.size}")
            epubBook?.toc?.forEach { tocItem ->
                android.util.Log.d("LibraryDrawer", "  TOC Item: ${tocItem.title} -> ${tocItem.href}")
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
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
                                // Load first chapter
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
                                // Delete item
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

            // Expanded TOC
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

/**
 * Recursive TOC item view with indentation for hierarchy
 */
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
    val startPadding: androidx.compose.ui.unit.Dp = when (depth) {
        0 -> 0.dp
        1 -> 16.dp
        2 -> 32.dp
        else -> 48.dp
    }

    Column {
        // TOC item row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = {
                        // Load this chapter
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

        // Render children if expanded
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
