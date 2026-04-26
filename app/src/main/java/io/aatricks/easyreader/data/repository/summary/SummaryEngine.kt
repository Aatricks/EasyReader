package io.aatricks.easyreader.data.repository.summary

/**
 * Interface for AI summarization engines.
 */
interface SummaryEngine {
    /**
     * Check if the engine is available and ready to use.
     */
    fun isAvailable(): Boolean

    /**
     * Initialize the engine (e.g., load models).
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Generate a summary for the given text.
     * @param prompt The prompt for summarization.
     * @param onProgress Callback for streaming progress.
     */
    suspend fun generateSummary(
        prompt: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String>

    /**
     * Cancel any ongoing generation.
     */
    fun cancelGeneration()

    /**
     * Release resources used by the engine.
     */
    fun release()
}
