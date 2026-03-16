package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.ExploreRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
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

    private val _searchQueryFlow = MutableStateFlow("")

    init {
        updateState { it.copy(sources = exploreRepository.getSourceNames()) }
        loadInitialData()

        viewModelScope.launch {
            _searchQueryFlow
                .drop(1)
                .debounce(500L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isNotBlank()) {
                        performSearch()
                    } else {
                        loadInitialData()
                    }
                }
        }
    }

    private var currentJob: Job? = null

    private fun loadInitialData(): Unit {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                val tags = exploreRepository.getTags(_uiState.value.selectedSource)
                updateState { it.copy(isLoading = true, availableTags = tags) }
                val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, _uiState.value.selectedTags.toList())
                updateState { it.copy(items = novels, isLoading = false, page = 1) }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    fun updateSearchQuery(query: String): Unit {
        updateState { it.copy(searchQuery = query, isSearching = query.isNotBlank()) }
        _searchQueryFlow.value = query
    }

    fun toggleSearch(): Unit {
        val currentlySearching = _uiState.value.isSearching
        if (currentlySearching) {
            updateState { it.copy(isSearching = false, searchQuery = "") }
            _searchQueryFlow.value = ""
            loadInitialData()
        } else {
            updateState { it.copy(isSearching = true) }
        }
    }

    fun selectSource(sourceName: String?): Unit {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                val searchQuery = _uiState.value.searchQuery.trim()
                updateState {
                    it.copy(
                        selectedSource = sourceName,
                        isLoading = true,
                        page = 1,
                        selectedTags = emptySet()
                    )
                }
                val tags = exploreRepository.getTags(sourceName)
                val novels = if (searchQuery.isNotBlank()) {
                    exploreRepository.searchNovels(searchQuery, 1, sourceName)
                } else {
                    exploreRepository.getPopularNovels(1, sourceName, emptyList())
                }
                updateState {
                    it.copy(
                        items = novels,
                        isLoading = false,
                        availableTags = tags,
                        isSearching = searchQuery.isNotBlank()
                    )
                }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleTag(tag: String): Unit {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                val newTags = if (_uiState.value.selectedTags.contains(tag)) {
                    _uiState.value.selectedTags - tag
                } else {
                    _uiState.value.selectedTags + tag
                }
                updateState { it.copy(selectedTags = newTags, isLoading = true, page = 1) }
                val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, newTags.toList())
                updateState { it.copy(items = novels, isLoading = false) }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    fun clearTags(): Unit {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                updateState { it.copy(selectedTags = emptySet(), isLoading = true, page = 1) }
                val novels = exploreRepository.getPopularNovels(1, _uiState.value.selectedSource, emptyList())
                updateState { it.copy(items = novels, isLoading = false) }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    fun performSearch(): Unit {
        if (_uiState.value.searchQuery.isBlank()) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true, page = 1) }
                val novels = exploreRepository.searchNovels(_uiState.value.searchQuery, 1, _uiState.value.selectedSource)
                updateState { it.copy(items = novels, isLoading = false) }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    fun loadMore(): Unit {
        if (_uiState.value.isLoading) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                updateState { it.copy(isLoading = true) }
                val nextPage = _uiState.value.page + 1
                val newItems = fetchItems(nextPage)
                
                val existingUrls = _uiState.value.items.map { it.url }.toSet()
                val distinctNewItems = newItems.filter { newItem -> 
                    !existingUrls.contains(newItem.url)
                }
                
                updateState { it.copy(
                    items = it.items + distinctNewItems,
                    isLoading = false,
                    page = if (distinctNewItems.isNotEmpty()) nextPage else it.page
                ) }
            }.onFailure {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun fetchItems(page: Int): List<ExploreItem> {
        return if (_uiState.value.isSearching && _uiState.value.searchQuery.isNotBlank()) {
            exploreRepository.searchNovels(_uiState.value.searchQuery, page, _uiState.value.selectedSource)
        } else {
            exploreRepository.getPopularNovels(page, _uiState.value.selectedSource, _uiState.value.selectedTags.toList())
        }
    }

    fun selectItem(item: ExploreItem): Unit {
        updateState { it.copy(selectedItem = item, selectedItemDetails = null, isFetchingDetails = true) }
        viewModelScope.launch {
            runCatching {
                val details = exploreRepository.getNovelDetails(item.url, item.source)
                updateState { it.copy(selectedItemDetails = details ?: item, isFetchingDetails = false) }
            }.onFailure {
                updateState { it.copy(isFetchingDetails = false, selectedItemDetails = item) }
            }
        }
    }

    fun dismissItem(): Unit {
        updateState { it.copy(selectedItem = null, selectedItemDetails = null) }
    }

    fun clearFilters(): Unit {
        currentJob?.cancel()
        updateState {
            it.copy(
                searchQuery = "",
                isSearching = false,
                selectedSource = null,
                selectedTags = emptySet(),
                page = 1
            )
        }
        loadInitialData()
    }

}
