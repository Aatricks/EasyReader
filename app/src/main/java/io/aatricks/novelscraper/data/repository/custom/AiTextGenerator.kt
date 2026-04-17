package io.aatricks.novelscraper.data.repository.custom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.llmedge.LLMEdge
import io.aatricks.llmedge.model.ModelSpec
import io.aatricks.llmedge.text.TextGenerationRequest
import io.aatricks.llmedge.text.TextModelOptions
import io.aatricks.llmedge.text.runtime.SmolLM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface AiTextGenerator {
    suspend fun generate(systemPrompt: String, prompt: String, maxTokens: Int = 768): Result<String>
}

@Singleton
class LlmEdgeTextGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) : AiTextGenerator {

    companion object {
        private const val MODEL_ID = "unsloth/Qwen3-0.6B-GGUF"
        private const val MODEL_FILENAME = "Qwen3-0.6B-Q4_K_M.gguf"
    }

    @Volatile
    private var isInitialized = false
    private var edgeScope: CoroutineScope? = null
    private var edge: LLMEdge? = null
    private var initDeferred: CompletableDeferred<Result<Unit>>? = null
    private val initLock = Any()
    private val modelSpec = ModelSpec.huggingFace(
        repoId = MODEL_ID,
        filename = MODEL_FILENAME
    )

    override suspend fun generate(systemPrompt: String, prompt: String, maxTokens: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            initialize().getOrThrow()
            val request = TextGenerationRequest(
                prompt = prompt,
                model = modelSpec,
                systemPrompt = systemPrompt,
                options = TextModelOptions(
                    temperature = 0.1f,
                    thinkingMode = SmolLM.ThinkingMode.DISABLED,
                    reasoningBudget = 0
                ),
                maxTokens = maxTokens,
                batchSize = 8
            )
            getOrCreateEdge().text.generate(request).trim()
        }
    }

    private suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext Result.success(Unit)

        val waitFor = synchronized(initLock) {
            if (isInitialized) return@synchronized null
            initDeferred?.also { return@synchronized it }
            CompletableDeferred<Result<Unit>>().also { initDeferred = it }
        }

        if (waitFor != null && waitFor.isCompleted) {
            return@withContext waitFor.await()
        }
        if (waitFor != null && initDeferred !== waitFor) {
            return@withContext waitFor.await()
        }
        if (waitFor == null) return@withContext Result.success(Unit)

        val result = runCatching {
            getOrCreateEdge().models.prefetch(modelSpec)
            isInitialized = true
            Unit
        }

        waitFor.complete(result)
        synchronized(initLock) { initDeferred = null }
        result
    }

    private fun getOrCreateEdge(): LLMEdge {
        edge?.let { return it }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val created = LLMEdge.create(context, scope)
        edgeScope = scope
        edge = created
        return created
    }
}
