package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.repository.SummaryService
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
    fun initializeSummaryService(): Unit {
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
    ): Unit {
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
            
            val sb = StringBuilder()
            summaryService.generateSummary(chapterTitle, content, onProgress = { token ->
                sb.append(token)
                updateState { it.copy(currentSummary = sb.toString()) }
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
    ): Unit {
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

    private fun handleGenerationFailure(e: Throwable): Unit {
        val error = e.message ?: "Failed to generate summary"
        updateState { it.copy(
            isGenerating = false,
            activeChapterUrl = null,
            error = error
        ) }
    }

    fun cancelGeneration(): Unit {
        summaryService.cancelGeneration()
        updateState { it.copy(isGenerating = false, activeChapterUrl = null) }
    }
    
    fun getCachedSummary(chapterUrl: String): String? = _uiState.value.summariesCache[chapterUrl]
    
    fun clearError(): Unit {
        updateState { it.copy(error = null) }
    }
    
    fun isServiceReady(): Boolean = summaryService.isReady()
    
    override fun onCleared(): Unit {
        super.onCleared()
        summaryService.release()
    }
}