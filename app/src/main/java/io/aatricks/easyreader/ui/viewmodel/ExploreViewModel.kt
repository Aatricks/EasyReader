package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.model.ExploreItem
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.SourceFailure
import io.aatricks.easyreader.data.repository.source.BrowseMode
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
        val sources: List<String> = emptyList(),
        val canLoadMore: Boolean = true,
        val browseMode: BrowseMode = BrowseMode.POPULAR,
        val searchFailures: List<SourceFailure> = emptyList(),
        val hasError: Boolean = false
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
            updateState {
                it.copy(
                    isLoading = true,
                    canLoadMore = true,
                    hasError = false
                )
            }
            runCatching {
                val tags = exploreRepository.getTags(_uiState.value.selectedSource)
                updateState {
                    it.copy(
                        availableTags = tags,
                    )
                }
                val outcome = exploreRepository.getNovelsDetailed(
                    _uiState.value.browseMode,
                    1,
                    _uiState.value.selectedSource,
                    _uiState.value.selectedTags.toList()
                )
                updateState {
                    it.copy(
                        items = outcome.items,
                        isLoading = false,
                        page = 1,
                        canLoadMore = outcome.items.isNotEmpty(),
                        hasError = outcome.items.isEmpty() && outcome.failures.isNotEmpty()
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false, hasError = true) }
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
                        selectedTags = emptySet(),
                        selectedItem = null,
                        selectedItemDetails = null,
                        isFetchingDetails = false,
                        canLoadMore = true,
                        hasError = false
                    )
                }
                val tags = exploreRepository.getTags(sourceName)
                val novels: List<ExploreItem>
                val failures: List<SourceFailure>
                if (searchQuery.isNotBlank()) {
                    val outcome = exploreRepository.searchNovelsDetailed(searchQuery, 1, sourceName)
                    novels = outcome.items
                    failures = outcome.failures
                } else {
                    val outcome = exploreRepository.getNovelsDetailed(
                        _uiState.value.browseMode,
                        1,
                        sourceName,
                        emptyList()
                    )
                    novels = outcome.items
                    failures = outcome.failures
                }
                updateState {
                    it.copy(
                        items = novels,
                        isLoading = false,
                        availableTags = tags,
                        isSearching = searchQuery.isNotBlank(),
                        canLoadMore = novels.isNotEmpty(),
                        searchFailures = if (searchQuery.isNotBlank()) failures else emptyList(),
                        hasError = novels.isEmpty() && failures.isNotEmpty()
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false, hasError = true) }
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
                updateState {
                    it.copy(
                        selectedTags = newTags,
                        isLoading = true,
                        page = 1,
                        canLoadMore = true,
                        hasError = false
                    )
                }
                val outcome = exploreRepository.getNovelsDetailed(
                    _uiState.value.browseMode,
                    1,
                    _uiState.value.selectedSource,
                    newTags.toList()
                )
                updateState {
                    it.copy(
                        items = outcome.items,
                        isLoading = false,
                        canLoadMore = outcome.items.isNotEmpty(),
                        hasError = outcome.items.isEmpty() && outcome.failures.isNotEmpty()
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false, hasError = true) }
            }
        }
    }

    fun clearTags(): Unit {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                updateState {
                    it.copy(
                        selectedTags = emptySet(),
                        isLoading = true,
                        page = 1,
                        canLoadMore = true,
                        hasError = false
                    )
                }
                val outcome = exploreRepository.getNovelsDetailed(
                    _uiState.value.browseMode,
                    1,
                    _uiState.value.selectedSource,
                    emptyList()
                )
                updateState {
                    it.copy(
                        items = outcome.items,
                        isLoading = false,
                        canLoadMore = outcome.items.isNotEmpty(),
                        hasError = outcome.items.isEmpty() && outcome.failures.isNotEmpty()
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false, hasError = true) }
            }
        }
    }

    fun setBrowseMode(mode: BrowseMode): Unit {
        if (_uiState.value.browseMode == mode) return
        updateState { it.copy(browseMode = mode) }
        if (_uiState.value.searchQuery.isBlank()) {
            loadInitialData()
        }
    }

    fun performSearch(): Unit {
        if (_uiState.value.searchQuery.isBlank()) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            runCatching {
                updateState {
                    it.copy(
                        isLoading = true,
                        page = 1,
                        canLoadMore = true,
                        searchFailures = emptyList(),
                        hasError = false
                    )
                }
                val outcome = exploreRepository.searchNovelsDetailed(
                    _uiState.value.searchQuery,
                    1,
                    _uiState.value.selectedSource
                )
                updateState {
                    it.copy(
                        items = outcome.items,
                        isLoading = false,
                        canLoadMore = outcome.items.isNotEmpty(),
                        searchFailures = outcome.failures,
                        hasError = outcome.items.isEmpty() && outcome.failures.isNotEmpty()
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false, hasError = true) }
            }
        }
    }

    fun retryFailedSearchSource(sourceName: String): Unit {
        if (sourceName.isBlank()) {
            retry()
            return
        }
        if (_uiState.value.searchQuery.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val outcome = exploreRepository.searchNovelsDetailed(
                    _uiState.value.searchQuery,
                    _uiState.value.page,
                    sourceName
                )
                val existingUrls = _uiState.value.items.map { it.url }.toSet()
                val newItems = outcome.items.filter { it.url !in existingUrls }
                val remainingFailures = _uiState.value.searchFailures.filter { it.sourceName != sourceName } +
                    outcome.failures
                updateState {
                    it.copy(
                        items = it.items + newItems,
                        searchFailures = remainingFailures
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }
        }
    }

    fun loadMore(): Unit {
        if (_uiState.value.isLoading || !_uiState.value.canLoadMore) return
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
                    page = if (distinctNewItems.isNotEmpty()) nextPage else it.page,
                    canLoadMore = distinctNewItems.isNotEmpty()
                ) }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                updateState { it.copy(isLoading = false, canLoadMore = false) }
            }
        }
    }

    private suspend fun fetchItems(page: Int): List<ExploreItem> {
        return if (_uiState.value.isSearching && _uiState.value.searchQuery.isNotBlank()) {
            exploreRepository.searchNovels(_uiState.value.searchQuery, page, _uiState.value.selectedSource)
        } else {
            exploreRepository.getNovels(_uiState.value.browseMode, page, _uiState.value.selectedSource, _uiState.value.selectedTags.toList())
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
                selectedItem = null,
                selectedItemDetails = null,
                isFetchingDetails = false,
                page = 1,
                canLoadMore = true
            )
        }
        _searchQueryFlow.value = ""
        loadInitialData()
    }

    fun retry() {
        if (_uiState.value.searchQuery.isNotBlank()) {
            performSearch()
        } else {
            loadInitialData()
        }
    }

}
