package io.aatricks.easyreader.data.repository

import android.util.Log
import io.aatricks.easyreader.data.repository.summary.SummaryEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for generating AI summaries of novel chapters.
 * Uses a SummaryEngine for model management and inference.
 */
@Singleton
class SummaryService(
    private val summaryEngine: SummaryEngine,
    private val defaultDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(summaryEngine: SummaryEngine) : this(summaryEngine, Dispatchers.Default)
    
    companion object {
        private const val TAG = "SummaryService"
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
    
    /**
     * Whether the active build flavor includes the on-device summarization
     * engine. UI can use this to hide AI controls completely on the standard
     * flavor while still showing them (and offering opt-in) on the AI flavor.
     */
    fun supportsAi(): Boolean = summaryEngine.supportsAi

    /**
     * Initialize the summary model (lazy loading)
     */
    suspend fun initialize(): Result<Unit> = summaryEngine.initialize()
    
    /**
     * Generate a summary for the given chapter content
     */
    suspend fun generateSummary(
        chapterTitle: String?,
        content: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(defaultDispatcher) {
        runCatching {
            initialize().getOrThrow()
            
            val selectedContent = selectKeyContent(content, maxWords = 300)
            val prompt = buildPrompt(chapterTitle, selectedContent)
            
            Log.d(TAG, "Generating summary (${selectedContent.split(WHITESPACE_REGEX).size} words, ~${(selectedContent.length + prompt.length) / 4 + 200} tokens)")
            
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
            summaryEngine.generateSummary(prompt, onProgress).getOrThrow()
        } catch (e: Exception) {
            if (e.message?.contains("context size reached") == true) {
                Log.w(TAG, "Context size reached, retrying with shorter content")
                val shorterContent = selectKeyContent(fullContent, maxWords = 200)
                val retryPrompt = "Summarize this excerpt in 2-3 sentences:\n\n$shorterContent\n\nSummary:"
                summaryEngine.generateSummary(retryPrompt, onProgress).getOrThrow()
            } else throw e
        }
    }
    
    /**
     * Generate summary with shorter content
     */
    suspend fun generateQuickSummary(content: List<String>): Result<String> {
        return generateSummary(null, content.take(50))
    }
    
    /**
     * Cancel any ongoing generation
     */
    fun cancelGeneration(): Unit {
        summaryEngine.cancelGeneration()
    }
    
    /**
     * Release resources
     */
    fun release(): Unit {
        summaryEngine.release()
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
    fun isReady(): Boolean = summaryEngine.isAvailable()
}
