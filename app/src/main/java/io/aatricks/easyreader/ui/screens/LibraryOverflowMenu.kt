package io.aatricks.easyreader.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aatricks.easyreader.R
import io.aatricks.easyreader.data.model.SortMode
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

@Composable
private fun GroupBySourceMenuItem(groupBySource: Boolean, onClick: () -> Unit): Unit {
    val state = stringResource(if (groupBySource) R.string.common_on else R.string.common_off)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.library_group_by_source)) },
        leadingIcon = {
            if (groupBySource) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
            }
        },
        onClick = onClick,
        // The check icon is the only visual cue; without this every item reads the same aloud.
        modifier = Modifier.semantics {
            selected = groupBySource
            stateDescription = state
        }
    )
}

@Composable
private fun SortMenuItem(mode: SortMode, isSelected: Boolean, onClick: () -> Unit): Unit {
    val state = stringResource(
        if (isSelected) R.string.selected_icon_description else R.string.common_not_selected
    )
    DropdownMenuItem(
        text = {
            Text(
                stringResource(
                    when (mode) {
                        SortMode.LAST_READ -> R.string.library_sort_last_read
                        SortMode.DATE_ADDED -> R.string.library_sort_date_added
                        SortMode.TITLE -> R.string.library_sort_title
                        SortMode.PROGRESS -> R.string.library_sort_progress
                    }
                )
            )
        },
        leadingIcon = {
            if (isSelected) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
            }
        },
        onClick = onClick,
        // The check icon is the only visual cue; without this every item reads the same aloud.
        modifier = Modifier.semantics {
            selected = isSelected
            stateDescription = state
        }
    )
}

@Composable
internal fun LibraryOverflowMenu(
    sortMode: SortMode,
    onSortModeSelected: (SortMode) -> Unit,
    groupBySource: Boolean,
    onGroupBySourceChanged: (Boolean) -> Unit,
    onDownloadAll: () -> Unit
): Unit {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.library_more_options)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = stringResource(R.string.library_sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = EasyReaderSpacing.md,
                    vertical = EasyReaderSpacing.xs
                )
            )
            SortMode.entries.forEach { mode ->
                SortMenuItem(mode, isSelected = mode == sortMode) {
                    expanded = false
                    onSortModeSelected(mode)
                }
            }
            HorizontalDivider()
            GroupBySourceMenuItem(groupBySource) {
                expanded = false
                onGroupBySourceChanged(!groupBySource)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_download_all)) },
                leadingIcon = { Icon(imageVector = Icons.Default.Download, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDownloadAll()
                }
            )
        }
    }
}
