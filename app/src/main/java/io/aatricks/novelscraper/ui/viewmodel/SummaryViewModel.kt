package io.aatricks.novelscraper.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aatricks.novelscraper.data.repository.SummaryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

/**
 * ViewModel for managing AI chapter summaries
 * Coordinates with SummaryService and maintains UI state
 */
class SummaryViewModel(application: Application) : AndroidViewModel(application) {
    
    private val TAG = "SummaryViewModel"
    private val summaryService = SummaryService(application.applicationContext)
    
    // UI State
    data class SummaryUiState(
        val isInitializing: Boolean = false,
        val isGenerating: Boolean = false,
        val error: String? = null,
        val currentSummary: String? = null,
        val summariesCache: Map<String, String> = emptyMap() // chapterUrl -> summary
    )
    
    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()
    
    /**
     * Initialize the summary service (loads AI model)
     */
    fun initializeSummaryService() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInitializing = true, error = null)
            
            val result = summaryService.initialize()
            
            if (result.isSuccess) {
                Log.d(TAG, "Summary service initialized successfully")
                _uiState.value = _uiState.value.copy(isInitializing = false)
            } else {
                val error = result.exceptionOrNull()?.message ?: "Failed to initialize"
                Log.e(TAG, "Summary service initialization failed: $error")
                _uiState.value = _uiState.value.copy(
                    isInitializing = false,
                    error = error
                )
            }
        }
    }
    
    /**
     * Generate a summary for a chapter
     * @param chapterUrl Unique identifier for the chapter
     * @param chapterTitle Optional chapter title
     * @param content Chapter content (list of paragraphs)
     * @param onComplete Callback when summary is generated
     */
    fun generateSummary(
        chapterUrl: String,
        chapterTitle: String?,
        content: List<String>,
        onComplete: (String) -> Unit
    ) {
        // Check if already cached
        val cached = _uiState.value.summariesCache[chapterUrl]
        if (cached != null) {
            Log.d(TAG, "Using cached summary for: $chapterUrl")
            _uiState.value = _uiState.value.copy(currentSummary = cached)
            onComplete(cached)
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isGenerating = true,
                error = null,
                currentSummary = null
            )
            
            val result = summaryService.generateSummary(chapterTitle, content)
            
            if (result.isSuccess) {
                val summary = result.getOrNull() ?: "Summary generated"
                Log.d(TAG, "Summary generated for: $chapterUrl")
                
                // Update cache
                val updatedCache = _uiState.value.summariesCache.toMutableMap()
                updatedCache[chapterUrl] = summary
                
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    currentSummary = summary,
                    summariesCache = updatedCache
                )
                
                onComplete(summary)
            } else {
                val error = result.exceptionOrNull()?.message ?: "Failed to generate summary"
                Log.e(TAG, "Summary generation failed: $error")
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = error
                )
            }
        }
    }
    
    /**
     * Get cached summary if available
     */
    fun getCachedSummary(chapterUrl: String): String? {
        return _uiState.value.summariesCache[chapterUrl]
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Check if service is ready
     */
    fun isServiceReady(): Boolean {
        return summaryService.isReady()
    }
    
    override fun onCleared() {
        super.onCleared()
        summaryService.release()
        Log.d(TAG, "SummaryViewModel cleared")
    }
}
