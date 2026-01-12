package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.LLMEdgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    
    private val TAG = "SummaryService"
    private var modelFile: File? = null
    private var isInitialized = false
    private var isInitializing = false
    
    /**
     * Initialize the SmolLM model (lazy loading)
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext Result.success(Unit)
        
        if (isInitializing) {
            while (isInitializing) kotlinx.coroutines.delay(100)
            return@withContext if (isInitialized) Result.success(Unit) else Result.failure(Exception("Initialization failed"))
        }
        
        isInitializing = true
        
        runCatching {
            Log.d(TAG, "Downloading and ensuring model via LLMEdgeManager for chapter summarization")

            val modelId = "unsloth/Qwen3-0.6B-GGUF"
            val modelFilename = "Qwen3-0.6B-Q4_K_M.gguf"

            val downloadedFile = LLMEdgeManager.downloadModel(
                context = context,
                modelId = modelId,
                filename = modelFilename,
                preferSystemDownloader = true
            )

            Log.d(TAG, "Model ready: ${downloadedFile.name} (path=${downloadedFile.absolutePath})")

            modelFile = downloadedFile
            LLMEdgeManager.preferPerformanceMode = false
            isInitialized = true
            isInitializing = false
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize SmolLM", e)
            isInitializing = false
        }
    }
    
    /**
     * Generate a summary for the given chapter content
     */
    suspend fun generateSummary(
        chapterTitle: String?,
        content: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            initialize().getOrThrow()
            
            val selectedContent = selectKeyContent(content, maxWords = 300)
            val prompt = buildPrompt(chapterTitle, selectedContent)
            
            Log.d(TAG, "Generating summary (${selectedContent.split(Regex("\\s+")).size} words, ~${(selectedContent.length + prompt.length) / 4 + 200} tokens)")
            
            generateWithRetry(prompt, selectedContent, content, onProgress)
        }.onFailure { e ->
            Log.e(TAG, "Failed to generate summary", e)
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
        return withContext(Dispatchers.IO) { 
            LLMEdgeManager.generateText(context, params, onProgress).trim()
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
        runCatching { LLMEdgeManager.cancelGeneration() }
        modelFile = null
        isInitialized = false
        Log.d(TAG, "SummaryService released")
    }
    
    /**
     * Smart content selection for better summaries.
     */
    private fun selectKeyContent(content: List<String>, maxWords: Int): String {
        if (content.isEmpty()) return ""
        
        val wordsPerParagraph = content.map { it.split(Regex("\\s+")) }
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
    fun isReady(): Boolean = isInitialized && modelFile != null
}
