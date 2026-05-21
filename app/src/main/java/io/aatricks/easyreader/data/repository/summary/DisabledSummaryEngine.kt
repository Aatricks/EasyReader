package io.aatricks.easyreader.data.repository.summary

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A dummy implementation of [SummaryEngine] used when AI summarization is disabled.
 */
@Singleton
class DisabledSummaryEngine @Inject constructor() : SummaryEngine {
    override val supportsAi: Boolean = false

    override fun isAvailable(): Boolean = false

    override suspend fun initialize(): Result<Unit> = 
        Result.failure(IllegalStateException("AI Summarization is disabled in this build."))

    override suspend fun generateSummary(
        prompt: String,
        onProgress: ((String) -> Unit)?
    ): Result<String> = 
        Result.failure(IllegalStateException("AI Summarization is disabled in this build."))

    override fun cancelGeneration() {
        // No-op
    }

    override fun release() {
        // No-op
    }
}
