package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.repository.SummaryService
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

/**
 * ViewModel for managing AI chapter summaries
 * Coordinates with SummaryService and maintains UI state
 */
@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val summaryService: SummaryService,
    private val preferencesManager: PreferencesManager
) : BaseViewModel<SummaryViewModel.SummaryUiState>(
    SummaryUiState(
        supportsAi = summaryService.supportsAi(),
        isEnabled = summaryService.supportsAi() && preferencesManager.aiSummaryEnabled
    )
) {

    private val TAG = "SummaryViewModel"

    // UI State
    data class SummaryUiState(
        val supportsAi: Boolean = false,
        val isEnabled: Boolean = false,
        val isInitializing: Boolean = false,
        val isGenerating: Boolean = false,
        val error: String? = null,
        val currentSummary: String? = null,
        val activeChapterUrl: String? = null,
        val summariesCache: Map<String, String> = emptyMap() // chapterUrl -> summary
    )

    /**
     * Toggle the AI summary opt-in. Enabling triggers a download/initialize;
     * disabling releases the in-memory engine. Persists across launches.
     */
    fun setAiSummaryEnabled(enabled: Boolean) {
        if (!_uiState.value.supportsAi) return
        if (_uiState.value.isEnabled == enabled) return
        preferencesManager.aiSummaryEnabled = enabled
        updateState { it.copy(isEnabled = enabled, error = null) }
        if (enabled) {
            initializeSummaryService()
        } else {
            summaryService.cancelGeneration()
            summaryService.release()
            updateState {
                it.copy(
                    isInitializing = false,
                    isGenerating = false,
                    activeChapterUrl = null,
                    currentSummary = null
                )
            }
        }
    }

    /**
     * Initialize the summary service (loads AI model)
     */
    fun initializeSummaryService() {
        val state = _uiState.value
        if (!state.supportsAi || !state.isEnabled) return
        viewModelScope.launch {
            updateState { it.copy(isInitializing = true, error = null) }

            summaryService.initialize()
                .onSuccess {
                    Log.d(TAG, "Summary service initialized successfully")
                    updateState { it.copy(isInitializing = false) }
                }
                .onFailure { e ->
                    val error = e.message ?: "Failed to initialize"
                    Log.e(TAG, "Summary service initialization failed: $error")
                    updateState { it.copy(isInitializing = false, error = error) }
                }
        }
    }

    /**
     * Generate a summary for a chapter
     */
    fun generateSummary(
        chapterUrl: String,
        chapterTitle: String?,
        content: List<String>,
        onComplete: (String) -> Unit
    ) {
        _uiState.value.summariesCache[chapterUrl]?.let { cached ->
            updateState { it.copy(currentSummary = cached) }
            onComplete(cached)
            return
        }

        viewModelScope.launch {
            updateState { it.copy(
                isGenerating = true,
                activeChapterUrl = chapterUrl,
                error = null,
                currentSummary = null
            ) }

            summaryService.generateSummary(chapterTitle, content, onProgress = { snapshot ->
                updateState { it.copy(currentSummary = snapshot) }
            }).onSuccess { summary ->
                handleGenerationSuccess(chapterUrl, summary, onComplete)
            }.onFailure { e ->
                handleGenerationFailure(e)
            }
        }
    }

    private fun handleGenerationSuccess(
        chapterUrl: String,
        summary: String,
        onComplete: (String) -> Unit
    ) {
        val updatedCache = _uiState.value.summariesCache.toMutableMap().apply {
            put(chapterUrl, summary)
        }

        updateState { it.copy(
            isGenerating = false,
            activeChapterUrl = null,
            currentSummary = summary,
            summariesCache = updatedCache
        ) }
        onComplete(summary)
    }

    private fun handleGenerationFailure(e: Throwable) {
        val error = e.message ?: "Failed to generate summary"
        updateState { it.copy(
            isGenerating = false,
            activeChapterUrl = null,
            error = error
        ) }
    }

    fun cancelGeneration() {
        summaryService.cancelGeneration()
        updateState { it.copy(isGenerating = false, activeChapterUrl = null) }
    }

    fun isServiceReady(): Boolean = summaryService.isReady()

    override fun onCleared() {
        super.onCleared()
        summaryService.release()
    }
}
