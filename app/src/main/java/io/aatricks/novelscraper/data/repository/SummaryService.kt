package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.LLMEdgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for generating AI summaries of novel chapters using llmedge library
 * Uses LLMEdgeManager (llmedge) for model management and on-device inference
 */
class SummaryService(private val context: Context) {
    
    private val TAG = "SummaryService"
    private var modelFile: File? = null
    private var isInitialized = false
    private var isInitializing = false
    
    /**
     * Initialize the SmolLM model (lazy loading)
     * Downloads a small model suitable for summarization if needed
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) {
            return@withContext Result.success(Unit)
        }
        
        if (isInitializing) {
            // Wait for existing initialization
            while (isInitializing) {
                kotlinx.coroutines.delay(100)
            }
            return@withContext if (isInitialized) Result.success(Unit) else Result.failure(Exception("Initialization failed"))
        }
        
        isInitializing = true
        
        try {
            Log.d(TAG, "Downloading and ensuring model via LLMEdgeManager for chapter summarization")

            val modelId = "unsloth/Qwen3-0.6B-GGUF"
            val modelFilename = "Qwen3-0.6B-Q4_K_M.gguf"

            val downloadedFile = LLMEdgeManager.downloadModel(
                context = context,
                modelId = modelId,
                filename = modelFilename,
                preferSystemDownloader = true
            )

            Log.d(TAG, "Model ready: ${'$'}{downloadedFile.name} (path=${'$'}{downloadedFile.absolutePath})")

            modelFile = downloadedFile
            // Prefer conservative performance mode for stability on mobile devices
            LLMEdgeManager.preferPerformanceMode = false
            isInitialized = true
            isInitializing = false
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmolLM", e)
            isInitializing = false
            Result.failure(e)
        }
    }
    
    /**
     * Generate a summary for the given chapter content
     * @param chapterTitle The chapter title (optional, for context)
     * @param content The chapter content (list of paragraphs)
     * @return Summary text or error message
     */
    suspend fun generateSummary(
        chapterTitle: String?,
        content: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            // Ensure initialization
            val initResult = initialize()
            if (initResult.isFailure) {
                return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("Initialization failed"))
            }
            
            // LLMEdgeManager will handle model loading/caching; ensure we have a model path
            val currentModelPath = modelFile?.absolutePath
            
            // Smart content selection for better summaries
            // Reduced to 300 words to ensure we stay well within token/context limits
            val selectedContent = selectKeyContent(content, maxWords = 300)
            
            val prompt = buildString {
                append("Read this chapter excerpt and provide a concise summary focusing on:\n")
                append("- Main plot developments\n")
                append("- Key character actions and decisions\n")
                append("- Important events or revelations\n\n")
                if (!chapterTitle.isNullOrBlank()) {
                    append("Chapter title: ${'$'}{chapterTitle}\n\n")
                }
                append("Chapter text:\n")
                append(selectedContent)
                append("\n\nProvide a 3-4 sentence summary:")
            }

            // Log token estimation
            val estimatedTokens = (selectedContent.length / 4) + (prompt.length / 4) + 200 // rough estimate
            Log.d(TAG, "Generating summary (${selectedContent.split(Regex("\\s+")).size} words, ${selectedContent.length} chars, ~${estimatedTokens} tokens)")
            
            var summary: String
            try {
                val params = LLMEdgeManager.TextGenerationParams(
                    prompt = prompt,
                    systemPrompt = """You are a concise chapter summarizer. 
                        |Your task is to read novel chapters and create brief, informative summaries.
                        |Focus on: main plot points, key character actions, and important events.
                        |Keep summaries to 2-3 sentences. Be factual and avoid speculation.""".trimMargin(),
                    modelId = "unsloth/Qwen3-0.6B-GGUF",
                    modelFilename = modelFile?.name ?: "Qwen3-0.6B-Q4_K_M.gguf",
                    modelPath = currentModelPath,
                    temperature = 0.3f,
                    maxTokens = 256,
                    thinkingMode = SmolLM.ThinkingMode.DISABLED,
                    reasoningBudget = 0
                )

                summary = withContext(Dispatchers.IO) { LLMEdgeManager.generateText(context, params, onProgress) }
            } catch (e: IllegalStateException) {
                if (e.message?.contains("context size reached") == true) {
                    Log.w(TAG, "Context size reached, trying with shorter content")
                    // Try with much shorter content - 300 words
                    val shorterContent = selectKeyContent(content, maxWords = 300)
                    val shorterPrompt = buildString {
                        append("Summarize this chapter excerpt in 2-3 sentences:\n\n")
                        append(shorterContent)
                        append("\n\nSummary:")
                    }
                    Log.d(TAG, "Retry: ${shorterContent.split(Regex("\\s+")).size} words, ${shorterContent.length} chars")
                    val params = LLMEdgeManager.TextGenerationParams(
                        prompt = shorterPrompt,
                        systemPrompt = "You are a concise chapter summarizer.",
                        modelId = "unsloth/Qwen3-0.6B-GGUF",
                        modelFilename = modelFile?.name ?: "Qwen3-0.6B-Q4_K_M.gguf",
                        modelPath = currentModelPath,
                        temperature = 0.3f,
                        thinkingMode = SmolLM.ThinkingMode.DISABLED,
                        reasoningBudget = 0
                    )
                    summary = withContext(Dispatchers.IO) { LLMEdgeManager.generateText(context, params, onProgress) }
                    Log.d(TAG, "Generated summary with shorter content")
                } else {
                    throw e
                }
            }
            
            Log.d(TAG, "Summary generated: ${summary.take(100)}...")
            
            Result.success(summary.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate summary", e)
            Result.failure(e)
        }
    }
    
    /**
     * Generate summary with shorter content (for faster generation)
     */
    suspend fun generateQuickSummary(content: List<String>): Result<String> {
        return generateSummary(null, content.take(50)) // Take first 50 paragraphs only
    }
    
    /**
     * Release resources
     */
    fun release() {
        // Cancel any ongoing generation and clear local references
        try {
            LLMEdgeManager.cancelGeneration()
        } catch (_: Throwable) {
        }
        modelFile = null
        isInitialized = false
        Log.d(TAG, "SummaryService released")
    }
    
    /**
     * Smart content selection for better summaries.
     */
    private fun selectKeyContent(content: List<String>, maxWords: Int): String {
        if (content.isEmpty()) return ""
        
        val totalWords = content.sumOf { it.split(Regex("\\s+")).size }
        if (totalWords <= maxWords) {
            return content.joinToString("\n\n")
        }
        
        val scoredParagraphs = content.mapIndexed { index, paragraph ->
            val words = paragraph.split(Regex("\\s+"))
            val wordCount = words.size
            
            var score = 0.0
            
            // Score based on length
            score += when {
                wordCount in 20..100 -> 2.0
                wordCount in 10..20 -> 1.0
                wordCount > 100 -> 1.5
                else -> 0.5
            }
            
            // Score based on position (favoring start and end)
            val position = index.toDouble() / content.size
            score += when {
                index < 3 -> 3.0
                index >= content.size - 3 -> 2.5
                position in 0.4..0.6 -> 1.5
                else -> 0.5
            }
            
            // Dialogue
            if (paragraph.contains("\"") || paragraph.contains("'") || 
                paragraph.contains("said") || paragraph.contains("asked")) score += 1.5
            
            // Keywords
            val keywordPatterns = listOf(
                "suddenly", "realized", "discovered", "decided", "arrived",
                "died", "killed", "attacked", "revealed", "secret",
                "important", "finally", "however", "but", "although",
                "shocked", "surprised", "angry", "happy", "sad"
            )
            val lowerParagraph = paragraph.lowercase()
            score += keywordPatterns.count { lowerParagraph.contains(it) } * 0.5
            
            // Action verbs
            val actionVerbs = listOf(
                "ran", "fought", "grabbed", "rushed", "jumped",
                "fell", "screamed", "whispered", "turned", "opened"
            )
            score += actionVerbs.count { lowerParagraph.contains(it) } * 0.3
            
            ScoredParagraph(index, paragraph, wordCount, score)
        }
        
        val sortedByScore = scoredParagraphs.sortedByDescending { it.score }
        val selected = mutableListOf<ScoredParagraph>()
        var currentWords = 0
        
        for (paragraph in sortedByScore) {
            if (currentWords + paragraph.wordCount <= maxWords) {
                selected.add(paragraph)
                currentWords += paragraph.wordCount
            }
            if (currentWords >= maxWords * 0.9) break
        }
        
        selected.sortBy { it.index }
        return selected.joinToString("\n\n") { it.text }
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
