package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.LLMEdge
import io.aatricks.llmedge.model.ModelSpec
import io.aatricks.llmedge.text.TextGenerationRequest
import io.aatricks.llmedge.text.TextModelOptions
import io.aatricks.llmedge.text.TextStreamEvent
import io.aatricks.llmedge.text.runtime.SmolLM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for generating AI summaries of novel chapters using llmedge library
 * Uses LLMEdgeManager (llmedge) for model management and on-device inference
 */
@Singleton
class SummaryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "SummaryService"
        private val WHITESPACE_REGEX = Regex("\\s+")
    }

    private var edgeScope: CoroutineScope? = null
    private var edge: LLMEdge? = null
    @Volatile
    private var isInitialized = false
    private var initDeferred: CompletableDeferred<Result<Unit>>? = null
    private val initLock = Any()
    private val modelSpec = ModelSpec.huggingFace(
        repoId = "unsloth/Qwen3-0.6B-GGUF",
        filename = "Qwen3-0.6B-Q4_K_M.gguf"
    )
    
    /**
     * Initialize the SmolLM model (lazy loading)
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext Result.success(Unit)
        
        val deferred = synchronized(initLock) {
            if (isInitialized) return@synchronized null
            initDeferred?.let { return@synchronized it }
            CompletableDeferred<Result<Unit>>().also { initDeferred = it }
        }
        
        // Another coroutine is already initializing — just await
        if (deferred != null && deferred.isCompleted.not() && initDeferred !== deferred) {
            return@withContext deferred.await()
        }
        if (deferred == null) return@withContext Result.success(Unit)
        
        val result = runCatching {
            Log.d(TAG, "Prefetching llmedge model for chapter summarization")
            val modelFile = getOrCreateEdge().models.prefetch(modelSpec)
            Log.d(TAG, "Model ready: ${modelFile.name} (path=${modelFile.absolutePath})")
            isInitialized = true
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize llmedge text runtime", e)
        }
        
        deferred.complete(result)
        synchronized(initLock) { initDeferred = null }
        result
    }
    
    /**
     * Generate a summary for the given chapter content
     */
    suspend fun generateSummary(
        chapterTitle: String?,
        content: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            initialize().getOrThrow()

            val selectedContent = selectKeyContent(content, maxWords = 300)
            val prompt = buildPrompt(chapterTitle, selectedContent)

            Log.d(TAG, "Generating summary (${selectedContent.split(WHITESPACE_REGEX).size} words)")

            Result.success(generateWithRetry(prompt, selectedContent, content, onProgress))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate summary", e)
            Result.failure(e)
        }
    }

    private fun buildPrompt(chapterTitle: String?, content: String): String = buildString {
        append("Read this chapter excerpt and provide a concise summary focusing on:\n")
        append("- Main plot developments\n- Key character actions\n- Important events\n\n")
        if (!chapterTitle.isNullOrBlank()) append("Chapter title: $chapterTitle\n\n")
        append("Chapter text:\n$content\n\nProvide a 3-4 sentence summary:")
    }

    private suspend fun generateWithRetry(
        prompt: String,
        selectedContent: String,
        fullContent: List<String>,
        onProgress: ((String) -> Unit)?
    ): String {
        return try {
            generateText(prompt, onProgress)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("context size reached") == true) {
                Log.w(TAG, "Context size reached, retrying with shorter content")
                val shorterContent = selectKeyContent(fullContent, maxWords = 200)
                val retryPrompt = "Summarize this excerpt in 2-3 sentences:\n\n$shorterContent\n\nSummary:"
                generateText(retryPrompt, onProgress)
            } else throw e
        }
    }

    private suspend fun generateText(prompt: String, onProgress: ((String) -> Unit)?): String {
        val request = TextGenerationRequest(
            prompt = prompt,
            model = modelSpec,
            systemPrompt = "You are a concise chapter summarizer.",
            options = TextModelOptions(
                temperature = 0.3f,
                thinkingMode = SmolLM.ThinkingMode.DISABLED,
                reasoningBudget = 0
            ),
            maxTokens = 256,
            batchSize = 8
        )

        val chunks = StringBuilder()
        var completedText: String? = null
        return withContext(Dispatchers.IO) {
            getOrCreateEdge().text.stream(request).collect { event ->
                when (event) {
                    is TextStreamEvent.Chunk -> {
                        chunks.append(event.value)
                        onProgress?.invoke(event.value)
                    }
                    is TextStreamEvent.Completed -> {
                        completedText = event.fullText
                    }
                    else -> Unit
                }
            }
            (completedText ?: chunks.toString()).trim()
        }
    }
    
    /**
     * Generate summary with shorter content
     */
    suspend fun generateQuickSummary(content: List<String>): Result<String> {
        return generateSummary(null, content.take(50))
    }
    
    /**
     * Release resources
     */
    fun release(): Unit {
        runCatching { edge?.close() }
        runCatching { edgeScope?.cancel() }
        edge = null
        edgeScope = null
        isInitialized = false
        Log.d(TAG, "SummaryService released")
    }
    
    /**
     * Smart content selection for better summaries.
     */
    private fun selectKeyContent(content: List<String>, maxWords: Int): String {
        if (content.isEmpty()) return ""
        
        val wordsPerParagraph = content.map { it.split(WHITESPACE_REGEX) }
        val totalWords = wordsPerParagraph.sumOf { it.size }
        if (totalWords <= maxWords) return content.joinToString("\n\n")
        
        val scoredParagraphs = content.indices.map { i ->
            val words = wordsPerParagraph[i]
            ScoredParagraph(i, content[i], words.size, calculateParagraphScore(i, content[i], words.size, content.size))
        }
        
        return scoredParagraphs.sortedByDescending { it.score }
            .fold(mutableListOf<ScoredParagraph>()) { acc, p ->
                if (acc.sumOf { it.wordCount } + p.wordCount <= maxWords) acc.add(p)
                acc
            }.sortedBy { it.index }
            .joinToString("\n\n") { it.text }
    }

    private fun calculateParagraphScore(index: Int, text: String, wordCount: Int, totalSize: Int): Double {
        var score = 0.0
        
        score += when (wordCount) {
            in 20..100 -> 2.0
            in 10..20 -> 1.0
            in 101..Int.MAX_VALUE -> 1.5
            else -> 0.5
        }
        
        val position = index.toDouble() / totalSize
        score += when {
            index < 3 -> 3.0
            index >= totalSize - 3 -> 2.5
            position in 0.4..0.6 -> 1.5
            else -> 0.5
        }
        
        val lowerText = text.lowercase()
        if (text.contains("\"") || text.contains("'") || lowerText.contains("said") || lowerText.contains("asked")) {
            score += 1.5
        }
        
        val keywords = listOf("suddenly", "realized", "discovered", "decided", "arrived", "died", "killed", "attacked", "revealed", "secret", "important", "finally", "however", "shocked", "surprised")
        score += keywords.count { lowerText.contains(it) } * 0.5
        
        val verbs = listOf("ran", "fought", "grabbed", "rushed", "jumped", "fell", "screamed", "whispered", "turned", "opened")
        score += verbs.count { lowerText.contains(it) } * 0.3
        
        return score
    }
    
    /**
     * Data class for scored paragraphs
     */
    private data class ScoredParagraph(
        val index: Int,
        val text: String,
        val wordCount: Int,
        val score: Double
    )
    
    /**
     * Check if service is ready
     */
    fun isReady(): Boolean = isInitialized

    private fun getOrCreateEdge(): LLMEdge {
        edge?.let { return it }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val created = LLMEdge.create(context, scope)
        edgeScope = scope
        edge = created
        return created
    }
}
