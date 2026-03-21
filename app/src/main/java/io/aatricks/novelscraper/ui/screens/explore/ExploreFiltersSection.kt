package io.aatricks.novelscraper.ui.screens.explore

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.aatricks.novelscraper.ui.theme.EasyReaderSpacing
import io.aatricks.novelscraper.ui.viewmodel.ExploreViewModel

@Composable
internal fun ExploreFilterPanel(
    uiState: ExploreViewModel.ExploreUiState,
    hasActiveFilters: Boolean,
    isCompactHeader: Boolean,
    compactSummaryState: LazyListState,
    sourceRowState: LazyListState,
    tagRowState: LazyListState,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit,
    onClearFilters: () -> Unit
): Unit {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isCompactHeader) 0.18f else 0.25f),
        modifier = Modifier.animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EasyReaderSpacing.md, vertical = EasyReaderSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.sm)
        ) {
            SearchField(
                query = uiState.searchQuery,
                onQueryChange = onSearchQueryChange,
                onPerformSearch = onPerformSearch
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        uiState.searchQuery.isNotBlank() && uiState.selectedSource != null ->
                            "Searching in ${uiState.selectedSource}"
                        uiState.searchQuery.isNotBlank() ->
                            "Searching all sources"
                        uiState.selectedSource != null ->
                            "Browsing ${uiState.selectedSource}"
                        else -> "Browsing popular titles"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (hasActiveFilters) {
                    TextButton(
                        onClick = onClearFilters,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("Clear all")
                    }
                }
            }

            if (isCompactHeader) {
                CompactFilterSummary(
                    selectedSource = uiState.selectedSource,
                    selectedTags = uiState.selectedTags,
                    listState = compactSummaryState,
                    onClearTags = onClearTags,
                    onSourceSelect = onSourceSelect
                )
            } else {
                ExpandedFilterControls(
                    uiState = uiState,
                    sourceRowState = sourceRowState,
                    tagRowState = tagRowState,
                    onSourceSelect = onSourceSelect,
                    onTagToggle = onTagToggle,
                    onClearTags = onClearTags
                )
            }
        }
    }
}

@Composable
private fun ExpandedFilterControls(
    uiState: ExploreViewModel.ExploreUiState,
    sourceRowState: LazyListState,
    tagRowState: LazyListState,
    onSourceSelect: (String?) -> Unit,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit
) {
    FilterSectionLabel("Sources")
    SourceRow(
        selectedSource = uiState.selectedSource,
        sources = uiState.sources,
        listState = sourceRowState,
        onSourceSelect = onSourceSelect
    )

    if (uiState.availableTags.isNotEmpty()) {
        if (uiState.searchQuery.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterSectionLabel("Genres")
                if (uiState.selectedTags.isNotEmpty()) {
                    TextButton(
                        onClick = onClearTags,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("Clear genres")
                    }
                }
            }
            TagRow(
                availableTags = uiState.availableTags,
                selectedTags = uiState.selectedTags,
                listState = tagRowState,
                onTagToggle = onTagToggle,
                onClearTags = onClearTags
            )
        } else if (uiState.selectedTags.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = EasyReaderSpacing.sm, vertical = EasyReaderSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xxs)
                ) {
                    Text(
                        text = "Saved genre filters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.selectedTags.joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFilterSummary(
    selectedSource: String?,
    selectedTags: Set<String>,
    listState: LazyListState,
    onClearTags: () -> Unit,
    onSourceSelect: (String?) -> Unit
) {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSourceSelect(null) },
                label = { Text("All sources") }
            )
        }
        selectedSource?.let { source ->
            item {
                FilterChip(
                    selected = true,
                    onClick = { onSourceSelect(null) },
                    label = { Text(source) }
                )
            }
        }
        if (selectedTags.isNotEmpty()) {
            item {
                FilterChip(
                    selected = true,
                    onClick = onClearTags,
                    label = { Text("${selectedTags.size} genres") }
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit
): Unit {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search titles or series") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onPerformSearch() }),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun FilterSectionLabel(text: String): Unit {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SourceRow(
    selectedSource: String?,
    sources: List<String>,
    listState: LazyListState,
    onSourceSelect: (String?) -> Unit
): Unit {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSourceSelect(null) },
                label = { Text("All sources") }
            )
        }
        items(sources) { sourceName ->
            FilterChip(
                selected = selectedSource == sourceName,
                onClick = {
                    onSourceSelect(if (selectedSource == sourceName) null else sourceName)
                },
                label = { Text(sourceName) }
            )
        }
    }
}

@Composable
private fun TagRow(
    availableTags: List<String>,
    selectedTags: Set<String>,
    listState: LazyListState,
    onTagToggle: (String) -> Unit,
    onClearTags: () -> Unit
): Unit {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EasyReaderSpacing.xs)
    ) {
        item {
            FilterChip(
                selected = selectedTags.isEmpty(),
                onClick = onClearTags,
                label = { Text("All") }
            )
        }
        items(availableTags) { tag ->
            FilterChip(
                selected = selectedTags.contains(tag),
                onClick = { onTagToggle(tag) },
                label = { Text(tag) }
            )
        }
    }
}
