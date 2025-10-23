package io.aatricks.novelscraper.data.model

/**
 * Sealed class representing different UI states for the reading screen.
 * Provides type-safe state management for loading, success, error, and empty states.
 */
sealed class ReadingState {
    /**
     * Loading state - content is being fetched or processed
     * @property message Optional loading message to display
     */
    data class Loading(val message: String? = null) : ReadingState()
    
    /**
     * Success state - content loaded successfully
     * @property content The chapter content to display
     */
    data class Success(val content: ChapterContent) : ReadingState()
    
    /**
     * Error state - an error occurred while loading content
     * @property message Error message to display to the user
     * @property throwable Optional exception for debugging
     * @property isRetryable Whether the operation can be retried
     */
    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val isRetryable: Boolean = true
    ) : ReadingState()
    
    /**
     * Empty state - no content available
     * @property message Optional message explaining why content is empty
     */
    data class Empty(val message: String? = null) : ReadingState()
    
    /**
     * Returns true if the state is loading
     */
    fun isLoading(): Boolean = this is Loading
    
    /**
     * Returns true if the state is success
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * Returns true if the state is error
     */
    fun isError(): Boolean = this is Error
    
    /**
     * Returns true if the state is empty
     */
    fun isEmpty(): Boolean = this is Empty
    
    /**
     * Safely get the content if state is Success, null otherwise
     */
    fun getContentOrNull(): ChapterContent? = (this as? Success)?.content
    
    /**
     * Safely get the error message if state is Error, null otherwise
     */
    fun getErrorMessageOrNull(): String? = (this as? Error)?.message
}
