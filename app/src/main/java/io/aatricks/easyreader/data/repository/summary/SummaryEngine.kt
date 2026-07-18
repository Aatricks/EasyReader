package io.aatricks.easyreader.data.repository.summary

/**
 * Interface for AI summarization engines.
 */
interface SummaryEngine {
    /**
     * Whether this build supports on-device AI summarization at all.
     * False for the standard flavor; true for the AI flavor regardless of
     * whether the model has been downloaded yet.
     */
    val supportsAi: Boolean
        get() = true

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
     * @param onProgress Callback receiving cumulative complete current summary snapshot.
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
