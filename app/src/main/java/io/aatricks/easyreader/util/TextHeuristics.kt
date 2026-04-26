package io.aatricks.easyreader.util

object TextHeuristics {

    private val WHITESPACE_REGEX = Regex("\\s+")

    val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')

    val CONTINUATION_WORDS = setOf(
        "of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her",
        "their", "its", "in", "on", "at", "from", "with"
    )

    fun lastWord(text: String): String {
        return text.trim().split(WHITESPACE_REGEX).lastOrNull() ?: ""
    }

    fun shouldMergeSentenceFragments(
        current: String,
        next: String,
        maxWordCount: Int = 8,
        isHeading: (String) -> Boolean = { false },
        preventDualColonMerge: Boolean = true
    ): Boolean {
        val lastChar = current.lastOrNull() ?: return false
        val lastWord = lastWord(current).lowercase()
        val wordCount = current.split(WHITESPACE_REGEX).size

        return !SENTENCE_ENDERS.contains(lastChar) &&
                (wordCount <= maxWordCount || lastWord in CONTINUATION_WORDS || lastWord.length <= 4) &&
                !isHeading(next) &&
                (!preventDualColonMerge || !(current.contains(':') && next.contains(':')))
    }

    fun shouldStopGreedyMerge(current: CharSequence, next: String): Boolean {
        val nextFirst = next.firstOrNull() ?: return true
        val currentLast = current.trim().lastOrNull() ?: return true
        return nextFirst.isUpperCase() && SENTENCE_ENDERS.contains(currentLast)
    }
}