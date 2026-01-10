package io.aatricks.novelscraper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.novelscraper.data.repository.SummaryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

/**
 * ViewModel for managing AI chapter summaries
 * Coordinates with SummaryService and maintains UI state
 */
@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val summaryService: SummaryService
) : BaseViewModel<SummaryViewModel.SummaryUiState>(SummaryUiState()) {
    
    private val TAG = "SummaryViewModel"
    
    // UI State
    data class SummaryUiState(
        val isInitializing: Boolean = false,
        val isGenerating: Boolean = false,
        val error: String? = null,
        val currentSummary: String? = null,
        val activeChapterUrl: String? = null,
        val summariesCache: Map<String, String> = emptyMap() // chapterUrl -> summary
    )
    
    /**
     * Initialize the summary service (loads AI model)
     */
    fun initializeSummaryService() {
        viewModelScope.launch {
            updateState { it.copy(isInitializing = true, error = null) }
            
            val result = summaryService.initialize()
            
            if (result.isSuccess) {
                Log.d(TAG, "Summary service initialized successfully")
                updateState { it.copy(isInitializing = false) }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Failed to initialize"
                Log.e(TAG, "Summary service initialization failed: $error")
                updateState { it.copy(
                    isInitializing = false,
                    error = error
                ) }
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
        val cached = _uiState.value.summariesCache[chapterUrl]
        if (cached != null) {
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
            
            val sb = StringBuilder()
            val result = summaryService.generateSummary(chapterTitle, content, onProgress = { token ->
                sb.append(token)
                updateState { it.copy(currentSummary = sb.toString()) }
            })
            
            if (result.isSuccess) {
                val summary = result.getOrNull() ?: "Summary generated"
                val updatedCache = _uiState.value.summariesCache.toMutableMap()
                updatedCache[chapterUrl] = summary
                
                updateState { it.copy(
                    isGenerating = false,
                    activeChapterUrl = null,
                    currentSummary = summary,
                    summariesCache = updatedCache
                ) }
                onComplete(summary)
            } else {
                val error = result.exceptionOrNull()?.message ?: "Failed to generate summary"
                updateState { it.copy(
                    isGenerating = false,
                    activeChapterUrl = null,
                    error = error
                ) }
            }
        }
    }

    fun cancelGeneration() {
        try { io.aatricks.llmedge.LLMEdgeManager.cancelGeneration() } catch (_: Throwable) {}
        updateState { it.copy(isGenerating = false, activeChapterUrl = null) }
    }
    
    fun getCachedSummary(chapterUrl: String): String? = _uiState.value.summariesCache[chapterUrl]
    
    fun clearError() {
        updateState { it.copy(error = null) }
    }
    
    fun isServiceReady(): Boolean = summaryService.isReady()
    
    override fun onCleared() {
        super.onCleared()
        summaryService.release()
    }
}