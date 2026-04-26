package io.aatricks.novelscraper.data.repository.summary

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.LLMEdgeManager
import io.aatricks.llmedge.SmolLM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SummaryEngine] implementation using the llmedge library.
 */
@Singleton
class LlmEdgeSummaryEngine @Inject constructor(
    private val context: Context
) : SummaryEngine {

    companion object {
        private const val TAG = "LlmEdgeSummaryEngine"
    }

    private var modelFile: File? = null
    @Volatile
    private var isInitialized = false
    private var initDeferred: CompletableDeferred<Result<Unit>>? = null
    private val initLock = Any()

    override fun isAvailable(): Boolean = isInitialized && modelFile != null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext Result.success(Unit)

        val deferred = synchronized(initLock) {
            if (isInitialized) return@synchronized null
            initDeferred?.let { return@synchronized it }
            CompletableDeferred<Result<Unit>>().also { initDeferred = it }
        }

        if (deferred != null && deferred.isCompleted.not() && initDeferred !== deferred) {
            return@withContext deferred.await()
        }
        if (deferred == null) return@withContext Result.success(Unit)

        val result = runCatching {
            Log.d(TAG, "Downloading and ensuring model via LLMEdgeManager")

            val modelId = "unsloth/Qwen3-0.6B-GGUF"
            val modelFilename = "Qwen3-0.6B-Q4_K_M.gguf"

            val downloadedFile = LLMEdgeManager.downloadModel(
                context = context,
                modelId = modelId,
                filename = modelFilename,
                preferSystemDownloader = true
            )

            Log.d(TAG, "Model ready: ${downloadedFile.name}")

            modelFile = downloadedFile
            LLMEdgeManager.preferPerformanceMode = false
            isInitialized = true
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize LLMEdge", e)
        }

        deferred.complete(result)
        synchronized(initLock) { initDeferred = null }
        result
    }

    override suspend fun generateSummary(
        prompt: String,
        onProgress: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val params = LLMEdgeManager.TextGenerationParams(
                prompt = prompt,
                systemPrompt = "You are a concise chapter summarizer.",
                modelId = "unsloth/Qwen3-0.6B-GGUF",
                modelFilename = modelFile?.name ?: "Qwen3-0.6B-Q4_K_M.gguf",
                modelPath = modelFile?.absolutePath,
                temperature = 0.3f,
                maxTokens = 256,
                thinkingMode = SmolLM.ThinkingMode.DISABLED
            )
            LLMEdgeManager.generateText(context, params, onProgress).trim()
        }
    }

    override fun cancelGeneration() {
        runCatching { LLMEdgeManager.cancelGeneration() }
    }

    override fun release() {
        cancelGeneration()
        modelFile = null
        isInitialized = false
        Log.d(TAG, "LlmEdgeSummaryEngine released")
    }
}
