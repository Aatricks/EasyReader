package io.aatricks.easyreader.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns library multi-select state (which item ids are selected, and whether selection mode is on),
 * extracted from LibraryViewModel. Group- and select-all operations take their ids from the caller
 * so this holder stays decoupled from the library list and ui state.
 */
class LibrarySelectionManager {
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    private val _selectionModeEnabled = MutableStateFlow(false)
    val selectionModeEnabled: StateFlow<Boolean> = _selectionModeEnabled.asStateFlow()

    val selectedIds: Set<String> get() = _selectedItems.value

    fun toggle(itemId: String) {
        _selectedItems.update {
            val current = it.toMutableSet()
            if (!current.add(itemId)) current.remove(itemId)
            current
        }
    }

    fun select(itemId: String) {
        _selectedItems.update { it + itemId }
    }

    fun deselect(itemId: String) {
        _selectedItems.update { it - itemId }
    }

    // Matches the original toggleGroupSelection: an empty id list is vacuously "all selected", so
    // it takes the subtract branch and is a no-op.
    fun toggleGroup(itemIds: List<String>) {
        val selected = _selectedItems.value
        val allSelected = itemIds.all { it in selected }
        val ids = itemIds.toSet()
        if (allSelected) {
            _selectedItems.update { it - ids }
        } else {
            _selectedItems.update { it + ids }
        }
    }

    fun selectAll(allIds: Set<String>) {
        _selectionModeEnabled.value = true
        _selectedItems.value = allIds
    }

    fun enterSelectionMode() {
        _selectionModeEnabled.value = true
    }

    fun clear() {
        _selectedItems.value = emptySet()
        _selectionModeEnabled.value = false
    }
}
