package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.ExploreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    val exploreRepository: ExploreRepository
) : BaseViewModel<ExploreViewModel.ExploreUiState>(ExploreUiState()) {

    data class ExploreUiState(
        val items: List<ExploreItem> = emptyList(),
        val isLoading: Boolean = false,
        val isSearching: Boolean = false,
        val searchQuery: String = "",
        val selectedSource: String? = null,
        val selectedTags: Set<String> = emptySet(),
        val availableTags: List<String> = emptyList(),
        val page: Int = 1,
        val selectedItem: ExploreItem? = null,
        val selectedItemDetails: ExploreItem? = null,
        val isFetchingDetails: Boolean = false,
        val sources: List<String> = emptyList()
    )

    init {
        updateState { it.copy(sources = exploreRepository.getSourceNames()) }
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val tags = exploreRepository.getTags(_uiState.value.selectedSource)
            updateState { it.copy(isLoading = true, availableTags = tags) }
            val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, _uiState.value.selectedTags.toList())
            updateState { it.copy(items = novels, isLoading = false, page = 1) }
        }
    }

    fun updateSearchQuery(query: String) {
        updateState { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        val currentlySearching = _uiState.value.isSearching
        if (currentlySearching) {
            updateState { it.copy(isSearching = false, searchQuery = "") }
            loadInitialData()
        } else {
            updateState { it.copy(isSearching = true) }
        }
    }

    fun selectSource(sourceName: String?) {
        viewModelScope.launch {
            updateState { it.copy(selectedSource = sourceName, isLoading = true, page = 1, selectedTags = emptySet()) }
            val tags = exploreRepository.getTags(sourceName)
            val novels = exploreRepository.getPopularNovels(1, sourceName, emptyList())
            updateState { it.copy(items = novels, isLoading = false, availableTags = tags) }
        }
    }

    fun toggleTag(tag: String) {
        viewModelScope.launch {
            val newTags = if (_uiState.value.selectedTags.contains(tag)) {
                _uiState.value.selectedTags - tag
            } else {
                _uiState.value.selectedTags + tag
            }
            updateState { it.copy(selectedTags = newTags, isLoading = true, page = 1) }
            val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, newTags.toList())
            updateState { it.copy(items = novels, isLoading = false) }
        }
    }

    fun clearTags() {
        viewModelScope.launch {
            updateState { it.copy(selectedTags = emptySet(), isLoading = true, page = 1) }
            val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, emptyList())
            updateState { it.copy(items = novels, isLoading = false) }
        }
    }

    fun performSearch() {
        if (_uiState.value.searchQuery.isBlank()) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true, page = 1) }
            val novels = exploreRepository.searchNovels(_uiState.value.searchQuery, 1, _uiState.value.selectedSource)
            updateState { it.copy(items = novels, isLoading = false) }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            val nextPage = _uiState.value.page + 1
            val newItems = if (_uiState.value.isSearching && _uiState.value.searchQuery.isNotBlank()) {
                exploreRepository.searchNovels(_uiState.value.searchQuery, nextPage, _uiState.value.selectedSource)
            } else {
                exploreRepository.getPopularNovels(nextPage, _uiState.value.selectedSource, _uiState.value.selectedTags.toList())
            }
            
            val distinctNewItems = newItems.filter { newItem -> 
                _uiState.value.items.none { it.url == newItem.url }
            }
            
            updateState { it.copy(
                items = it.items + distinctNewItems,
                isLoading = false,
                page = if (distinctNewItems.isNotEmpty()) nextPage else it.page
            ) }
        }
    }

    fun selectItem(item: ExploreItem) {
        updateState { it.copy(selectedItem = item, selectedItemDetails = null, isFetchingDetails = true) }
        viewModelScope.launch {
            val details = exploreRepository.getNovelDetails(item.url, item.source)
            updateState { it.copy(selectedItemDetails = details ?: item, isFetchingDetails = false) }
        }
    }

    fun dismissItem() {
        updateState { it.copy(selectedItem = null, selectedItemDetails = null) }
    }
}
