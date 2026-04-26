package io.aatricks.easyreader.data.repository.summary

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.model.ModelSpec
import io.aatricks.llmedge.text.TextClient
import io.aatricks.llmedge.text.TextGenerationRequest
import io.aatricks.llmedge.text.TextStreamEvent
import io.aatricks.llmedge.text.TextModelOptions
import io.aatricks.llmedge.text.runtime.SmolLM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * [SummaryEngine] implementation using the llmedge library.
 */
@Singleton
class LlmEdgeSummaryEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SummaryEngine {

    companion object {
        private const val TAG = "LlmEdgeSummaryEngine"
        private const val MODEL_ID = "unsloth/Qwen3-0.6B-GGUF"
        private const val MODEL_FILENAME = "Qwen3-0.6B-Q4_K_M.gguf"
        private const val SYSTEM_PROMPT = "You are a concise chapter summarizer."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelSpec = ModelSpec.huggingFace(MODEL_ID)
    private val modelOptions = TextModelOptions(
        contextSize = null,
        chatTemplate = null,
        numThreads = null,
        generationThreads = null,
        minP = null,
        temperature = 0.3f,
        useMmap = null,
        useMlock = null,
        useFlashAttention = null,
        thinkingMode = SmolLM.ThinkingMode.DISABLED,
        reasoningBudget = null,
        useVulkan = false
    )

    private var textClient: TextClient? = null
    @Volatile
    private var isInitialized = false
    private var initDeferred: CompletableDeferred<Result<Unit>>? = null
    private val initLock = Any()
    private var activeGenerationJob: Job? = null
    private val generationLock = Any()

    override fun isAvailable(): Boolean = isInitialized && textClient != null

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
            Log.d(TAG, "Creating llmedge text client for $MODEL_ID")

            val client = TextClient.create(context, scope)
            client.prepare(modelSpec, modelOptions)

            textClient = client
            isInitialized = true
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize LLMEdge", e)
            textClient?.close()
            textClient = null
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
            val client = textClient ?: error("Summary engine has not been initialized")
            val request = TextGenerationRequest(
                prompt = prompt,
                model = modelSpec,
                systemPrompt = SYSTEM_PROMPT,
                options = modelOptions,
                maxTokens = 256,
                batchSize = 1
            )

            val output = StringBuilder()
            val generationJob = coroutineContext[Job]
            synchronized(generationLock) { activeGenerationJob = generationJob }

            try {
                client.stream(request).collect { event ->
                    when (event) {
                        is TextStreamEvent.Started -> {
                            onProgress?.invoke("")
                        }
                        is TextStreamEvent.Chunk -> {
                            output.append(event.value)
                            onProgress?.invoke(output.toString())
                        }
                        is TextStreamEvent.Completed -> {
                            if (output.isEmpty()) {
                                output.append(event.fullText)
                            }
                        }
                    }
                }
            } finally {
                synchronized(generationLock) {
                    if (activeGenerationJob === generationJob) {
                        activeGenerationJob = null
                    }
                }
            }

            output.toString().trim()
        }
    }

    override fun cancelGeneration() {
        synchronized(generationLock) {
            activeGenerationJob?.cancel()
        }
    }

    override fun release() {
        cancelGeneration()
        textClient?.close()
        textClient = null
        scope.cancel()
        isInitialized = false
        Log.d(TAG, "LlmEdgeSummaryEngine released")
    }
}
