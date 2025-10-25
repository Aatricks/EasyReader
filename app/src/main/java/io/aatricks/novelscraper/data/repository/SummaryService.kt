package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.util.Log
import io.aatricks.llmedge.SmolLM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for generating AI summaries of novel chapters using llmedge library
 * Uses SmolLM for on-device inference with a small quantized model
 */
class SummaryService(private val context: Context) {
    
    private val TAG = "SummaryService"
    private var smolLM: SmolLM? = null
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
            Log.d(TAG, "Initializing SmolLM for chapter summarization")
            
            val llm = SmolLM(useVulkan = false) // Disable Vulkan for compatibility
            
            // Use a small quantized model for quick summaries
            // Qwen3-0.6B is a good choice for summarization (small and fast)
            val download = llm.loadFromHuggingFace(
                context = context,
                modelId = "unsloth/Qwen3-0.6B-GGUF",
                filename = "Qwen3-0.6B-Q4_K_M.gguf", // 4-bit quantized for memory efficiency
                params = SmolLM.InferenceParams(
                    numThreads = 2,
                    contextSize = 4096L, // Reduced from 8192 - more conservative
                    temperature = 0.3f, // Lower temperature for more focused summaries
                    storeChats = false,
                    thinkingMode = SmolLM.ThinkingMode.DISABLED,
                    reasoningBudget = 0
                ),
                preferSystemDownloader = true,
                forceDownload = false // Use cached model if available
            )
            
            Log.d(TAG, "Model loaded: ${download.file.name} (${if (download.fromCache) "cached" else "downloaded"})")
            
            // Set system prompt for summarization
            llm.addSystemPrompt("""You are a concise chapter summarizer. 
                |Your task is to read novel chapters and create brief, informative summaries.
                |Focus on: main plot points, key character actions, and important events.
                |Keep summaries to 2-3 sentences. Be factual and avoid speculation.""".trimMargin())
            
            smolLM = llm
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
        content: List<String>
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            // Ensure initialization
            val initResult = initialize()
            if (initResult.isFailure) {
                return@withContext Result.failure(initResult.exceptionOrNull() ?: Exception("Initialization failed"))
            }
            
            val llm = smolLM ?: return@withContext Result.failure(Exception("SmolLM not initialized"))
            
            // Smart content selection for better summaries
            // Reduced to 600 words to ensure we stay well within 8192 token limit
            val selectedContent = selectKeyContent(content, maxWords = 600)
            
            val prompt = buildString {
                append("Read this chapter excerpt and provide a concise summary focusing on:\n")
                append("- Main plot developments\n")
                append("- Key character actions and decisions\n")
                append("- Important events or revelations\n\n")
                append("Chapter text:\n")
                append(selectedContent)
                append("\n\nProvide a 3-4 sentence summary:")
            }

            // Log token estimation
            val estimatedTokens = (selectedContent.length / 4) + (prompt.length / 4) + 200 // rough estimate
            Log.d(TAG, "Generating summary (${selectedContent.split(Regex("\\s+")).size} words, ${selectedContent.length} chars, ~${estimatedTokens} tokens)")
            
            var summary: String
            try {
                summary = llm.getResponse(prompt)
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
                    summary = llm.getResponse(shorterPrompt)
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
        smolLM?.close()
        smolLM = null
        isInitialized = false
        Log.d(TAG, "SummaryService released")
    }
    
    /**
     * Smart content selection for better summaries
     * Implements multiple strategies:
     * - Content Analysis: Identify key paragraphs based on length, dialogue, keywords
     * - Middle Sampling: Include content from beginning, middle, and end
     * - Chapter Structure: Prioritize opening and closing paragraphs
     * 
     * @param content List of paragraphs
     * @param maxWords Maximum number of words to include
     * @return Selected content as a string
     */
    private fun selectKeyContent(content: List<String>, maxWords: Int): String {
        if (content.isEmpty()) return ""
        
        // If content is already small, return it all
        val totalWords = content.sumOf { it.split(Regex("\\s+")).size }
        if (totalWords <= maxWords) {
            return content.joinToString("\n\n")
        }
        
        Log.d(TAG, "Selecting key content from ${content.size} paragraphs ($totalWords words)")
        
        // Score each paragraph for importance
        val scoredParagraphs = content.mapIndexed { index, paragraph ->
            val words = paragraph.split(Regex("\\s+"))
            val wordCount = words.size
            
            var score = 0.0
            
            // 1. Length score: Prefer substantial paragraphs (not too short, not too long)
            score += when {
                wordCount in 20..100 -> 2.0
                wordCount in 10..20 -> 1.0
                wordCount > 100 -> 1.5
                else -> 0.5
            }
            
            // 2. Position score: Prioritize opening and closing
            val position = index.toDouble() / content.size
            score += when {
                index < 3 -> 3.0 // First 3 paragraphs
                index >= content.size - 3 -> 2.5 // Last 3 paragraphs
                position in 0.4..0.6 -> 1.5 // Middle section
                else -> 0.5
            }
            
            // 3. Dialogue score: Paragraphs with dialogue often contain key interactions
            val hasDialogue = paragraph.contains("\"") || paragraph.contains("'") || 
                             paragraph.contains("said") || paragraph.contains("asked")
            if (hasDialogue) score += 1.5
            
            // 4. Keyword score: Look for plot-relevant keywords
            val keywordPatterns = listOf(
                "suddenly", "realized", "discovered", "decided", "arrived",
                "died", "killed", "attacked", "revealed", "secret",
                "important", "finally", "however", "but", "although",
                "shocked", "surprised", "angry", "happy", "sad"
            )
            val lowerParagraph = paragraph.lowercase()
            val keywordCount = keywordPatterns.count { lowerParagraph.contains(it) }
            score += keywordCount * 0.5
            
            // 5. Action verbs score: Paragraphs with action are often important
            val actionVerbs = listOf(
                "ran", "fought", "grabbed", "rushed", "jumped",
                "fell", "screamed", "whispered", "turned", "opened"
            )
            val actionCount = actionVerbs.count { lowerParagraph.contains(it) }
            score += actionCount * 0.3
            
            ScoredParagraph(index, paragraph, wordCount, score)
        }
        
        // Sort by score (descending)
        val sortedByScore = scoredParagraphs.sortedByDescending { it.score }
        
        // Select paragraphs up to maxWords, maintaining some original order
        val selected = mutableListOf<ScoredParagraph>()
        var currentWords = 0
        
        for (paragraph in sortedByScore) {
            if (currentWords + paragraph.wordCount <= maxWords) {
                selected.add(paragraph)
                currentWords += paragraph.wordCount
            }
            if (currentWords >= maxWords * 0.9) break // Allow some flexibility
        }
        
        // Re-sort by original index to maintain narrative flow
        selected.sortBy { it.index }
        
        Log.d(TAG, "Selected ${selected.size} paragraphs ($currentWords words) from top-scored content")
        
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
    fun isReady(): Boolean = isInitialized && smolLM != null
}
