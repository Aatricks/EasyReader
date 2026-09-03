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
import io.aatricks.easyreader.data.model.SortMode
import io.aatricks.easyreader.ui.theme.EasyReaderSpacing

@Composable
private fun GroupBySourceMenuItem(groupBySource: Boolean, onClick: () -> Unit): Unit {
    DropdownMenuItem(
        text = { Text("Group by source") },
        leadingIcon = {
            if (groupBySource) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
            }
        },
        onClick = onClick
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
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = EasyReaderSpacing.md,
                    vertical = EasyReaderSpacing.xs
                )
            )
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (mode) {
                                SortMode.LAST_READ -> "Last read"
                                SortMode.DATE_ADDED -> "Date added"
                                SortMode.TITLE -> "Title"
                                SortMode.PROGRESS -> "Progress"
                            }
                        )
                    },
                    leadingIcon = {
                        if (mode == sortMode) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSortModeSelected(mode)
                    }
                )
            }
            HorizontalDivider()
            GroupBySourceMenuItem(groupBySource) {
                expanded = false
                onGroupBySourceChanged(!groupBySource)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Download all chapters") },
                leadingIcon = { Icon(imageVector = Icons.Default.Download, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDownloadAll()
                }
            )
        }
    }
}
