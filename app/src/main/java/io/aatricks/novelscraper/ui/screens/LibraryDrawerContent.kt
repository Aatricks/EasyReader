package io.aatricks.novelscraper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.ui.components.LibraryItemCard
import io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel
import io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel

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
@Composable
fun LibraryDrawerContent(
    libraryViewModel: io.aatricks.novelscraper.ui.viewmodel.LibraryViewModel,
    readerViewModel: io.aatricks.novelscraper.ui.viewmodel.ReaderViewModel,
    onOpenFilePicker: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val readerUiState by readerViewModel.uiState.collectAsState()
    
    var urlInput by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // URL Input Section
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("Novel URL", color = Color.Gray) },
            placeholder = { Text("Enter novel URL...", color = Color.DarkGray) },
            modifier = Modifier.fillMaxWidth(),
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
        
        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Add Button
            Button(
                onClick = {
                    if (urlInput.isNotBlank()) {
                        // Use addItem with a default inferred title and web content type
                        libraryViewModel.addItem(
                            title = urlInput, // fallback; repository may replace with fetched title
                            url = urlInput,
                            contentType = io.aatricks.novelscraper.data.model.ContentType.Web
                        )
                        urlInput = ""
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    disabledContainerColor = Color.DarkGray
                ),
                enabled = urlInput.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add", color = Color.White)
            }
            
            // Download Button (reload/cache placeholder)
            Button(
                onClick = { libraryViewModel.reload() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Download", color = Color.White)
            }
        }
        
        // Delete Selected Button (conditional)
        if (libraryUiState.isSelectionMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { libraryViewModel.removeSelectedItems() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5252)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete (${libraryUiState.selectedCount})", color = Color.White)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        HorizontalDivider(color = Color.DarkGray)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Library Items List
        if (libraryUiState.items.isEmpty()) {
            EmptyLibraryState()
        } else {
            LibraryItemsList(
                items = libraryUiState.items,
                selectedItems = libraryUiState.selectedCount.let { libraryUiState.items.filter { it.isSelected }.map { it.id }.toSet() },
                currentItemId = libraryUiState.currentlyReading?.id,
                onItemClick = { item ->
                    if (libraryUiState.isSelectionMode) {
                        libraryViewModel.toggleSelection(item.id)
                    } else {
                        // Load content in reader
                        readerViewModel.loadContent(item.url, item.id)
                        libraryViewModel.markAsCurrentlyReading(item.id)
                        onCloseDrawer()
                    }
                },
                onItemLongClick = { item ->
                    libraryViewModel.toggleSelection(item.id)
                }
            )
        }
    }
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
