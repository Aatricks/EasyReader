package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class BaseViewModel<S>(initialState: S) : ViewModel() {
    protected val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    protected fun updateState(block: (S) -> S) {
        _uiState.update(block)
    }

    protected fun launchWithStatus(
        handleLoading: Boolean = true,
        handleError: Boolean = true,
        loadingState: (S, Boolean) -> S,
        errorState: (S, String?) -> S,
        block: suspend CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch {
            try {
                if (handleLoading) updateState { loadingState(it, true) }
                if (handleError) updateState { errorState(it, null) }
                block()
                if (handleLoading) updateState { loadingState(it, false) }
            } catch (e: Exception) {
                if (handleLoading) updateState { loadingState(it, false) }
                if (handleError) updateState { errorState(it, e.message ?: "An unknown error occurred") }
            }
        }
    }
}
