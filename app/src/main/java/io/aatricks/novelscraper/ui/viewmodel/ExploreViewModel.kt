package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.model.ExploreItem
import io.aatricks.novelscraper.data.repository.ExploreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExploreViewModel(
    private val exploreRepository: ExploreRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    data class ExploreUiState(
        val items: List<ExploreItem> = emptyList(),
        val isLoading: Boolean = false,
        val page: Int = 1,
        val cloudflareChallengeUrl: String? = null,
        val searchQuery: String = ""
    )

    init {
        loadPopularNovels()
    }

    fun loadPopularNovels(page: Int = 1) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val items = exploreRepository.getPopularNovels(page)
            _uiState.update { 
                it.copy(
                    items = if (page == 1) items else it.items + items,
                    isLoading = false,
                    page = page,
                    searchQuery = ""
                )
            }
        }
    }

    fun searchNovels(query: String, page: Int = 1) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, searchQuery = query) }
            val items = exploreRepository.searchNovels(query, page)
            _uiState.update { 
                it.copy(
                    items = if (page == 1) items else it.items + items,
                    isLoading = false,
                    page = page
                )
            }
        }
    }

    fun onCloudflareBypassed() {
        val url = _uiState.value.cloudflareChallengeUrl
        _uiState.update { it.copy(cloudflareChallengeUrl = null) }
        if (url != null) {
            if (_uiState.value.searchQuery.isNotBlank()) {
                searchNovels(_uiState.value.searchQuery, _uiState.value.page)
            } else {
                loadPopularNovels(_uiState.value.page)
            }
        }
    }
    
    fun triggerCloudflareChallenge(url: String) {
        _uiState.update { it.copy(cloudflareChallengeUrl = url) }
    }
}
