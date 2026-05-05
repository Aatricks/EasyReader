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
import java.util.concurrent.CancellationException

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
    private var initWaiterCount = 0
    private val initLock = Any()
    private var activeGenerationJob: Job? = null
    private val generationLock = Any()

    internal var createTextClient: (Context, CoroutineScope) -> TextClient = TextClient::create
    internal var prepareTextClient: suspend (TextClient) -> Unit = { client ->
        client.prepare(modelSpec, modelOptions)
    }

    override fun isAvailable(): Boolean = isInitialized && textClient != null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        var deferred: CompletableDeferred<Result<Unit>>? = null
        var isOwner = false

        synchronized(initLock) {
            if (isInitialized) {
                return@withContext Result.success(Unit)
            }

            val existing = initDeferred
            if (existing != null) {
                deferred = existing
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                initDeferred = deferred
                isOwner = true
            }
            initWaiterCount += 1
        }

        val initWaiter = deferred ?: return@withContext Result.failure(
            IllegalStateException("Initialization deferred was not created")
        )

        if (!isOwner) {
            return@withContext try {
                initWaiter.await()
            } finally {
                finishInitializationWaiter(initWaiter)
            }
        }

        try {
            var client: TextClient? = null
            var handedOff = false

            val result = try {
                Log.d(TAG, "Creating llmedge text client for $MODEL_ID")

                val createdClient = createTextClient(context, scope)
                client = createdClient
                prepareTextClient(createdClient)

                synchronized(initLock) {
                    if (initDeferred === initWaiter) {
                        textClient = createdClient
                        isInitialized = true
                        handedOff = true
                    }
                }

                if (handedOff) {
                    Result.success(Unit)
                } else {
                    client?.close()
                    Result.failure(CancellationException("Summary engine initialization was cancelled"))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize LLMEdge", t)
                if (!handedOff) {
                    client?.close()
                }
                synchronized(initLock) {
                    if (!handedOff && textClient === client) {
                        textClient = null
                    }
                }
                Result.failure(t)
            }

            initWaiter.complete(result)

            result
        } finally {
            finishInitializationWaiter(initWaiter)
        }
    }

    private fun finishInitializationWaiter(initWaiter: CompletableDeferred<Result<Unit>>) {
        synchronized(initLock) {
            initWaiterCount -= 1
            if (initWaiterCount == 0 && initDeferred === initWaiter) {
                initDeferred = null
            }
        }
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
        val initializationDeferred = synchronized(initLock) {
            isInitialized = false
            val deferred = initDeferred
            initDeferred = null
            deferred
        }

        cancelGeneration()
        textClient?.close()
        textClient = null
        scope.cancel()

        if (initializationDeferred != null && initializationDeferred.isCompleted.not()) {
            initializationDeferred.complete(
                Result.failure(CancellationException("Summary engine was released"))
            )
        }

        Log.d(TAG, "LlmEdgeSummaryEngine released")
    }
}
