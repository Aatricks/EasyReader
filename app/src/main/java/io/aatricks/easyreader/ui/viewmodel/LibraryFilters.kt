package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.SortMode
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.seriesReadingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the library's filter/sort criteria (search text, content-type filter, sort mode, reading
 * status) and applies them to a list. Extracted from LibraryViewModel; the ViewModel exposes these
 * flows and feeds them plus the library items through [apply] in its state combine.
 */
class LibraryFilters {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _contentTypeFilter = MutableStateFlow<ContentType?>(null)
    val contentTypeFilter: StateFlow<ContentType?> = _contentTypeFilter.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.LAST_READ)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _statusFilter = MutableStateFlow(SeriesReadingStatus.ALL)
    val statusFilter: StateFlow<SeriesReadingStatus> = _statusFilter.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setContentTypeFilter(contentType: ContentType?) {
        _contentTypeFilter.value = contentType
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun setStatusFilter(filter: SeriesReadingStatus) {
        _statusFilter.value = filter
    }

    fun apply(
        items: List<LibraryItem>,
        query: String,
        filter: ContentType?,
        sort: SortMode,
        statusFilter: SeriesReadingStatus = SeriesReadingStatus.ALL
    ): List<LibraryItem> {
        var filtered = items

        if (filter != null) {
            filtered = filtered.filter { it.contentType == filter }
        }

        if (statusFilter != SeriesReadingStatus.ALL) {
            val seriesGroups = filtered.groupBy { it.libraryNovelKey() }
            val matchingKeys = seriesGroups
                .filterValues { groupItems -> seriesReadingStatus(groupItems) == statusFilter }
                .keys
            filtered = filtered.filter { it.libraryNovelKey() in matchingKeys }
        }

        if (query.isNotBlank()) {
            val lowercaseQuery = query.trim().lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(lowercaseQuery) ||
                it.baseTitle.lowercase().contains(lowercaseQuery)
            }
        }

        return when (sort) {
            SortMode.LAST_READ -> filtered.sortedByDescending { it.lastRead }
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.PROGRESS -> filtered.sortedByDescending { it.progress }
        }
    }
}
