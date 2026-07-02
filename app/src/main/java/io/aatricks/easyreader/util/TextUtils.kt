package io.aatricks.easyreader.util

import java.net.URI

/**
 * Utility functions for text processing and manipulation.
 * Handles text formatting, page number removal, and URL manipulation for chapter navigation.
 */
object TextUtils {

    // Pre-compiled Regex patterns for performance
    private val DIGIT_REGEX = Regex("\\d+")
    private val PAGE_WORD_REGEX = Regex("Page \\|\\s*|Page\\s+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MULTIPLE_SPACES_REGEX = Regex(" +\\n")

    private val EXTRACT_CHAPTER_LABEL_REGEX_1 = Regex("(?i)(?:chapter|ch|ch\\.|c)\\s*(\\d+)")
    private val EXTRACT_CHAPTER_LABEL_REGEX_2 = Regex("[\\s:\\-—–|](\\d+)\\s*$")
    private val EXTRACT_CHAPTER_LABEL_REGEX_3 = Regex("\\b(\\d+)\\b")

    private val EXTRACT_CHAPTER_LABEL_URL_PATTERNS = listOf(
        Regex("chapter\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("ch(?:apter)?\\D*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("/(\\d+)(?:/|$)"),
        Regex("-" + "(\\d+)(?:\\D|$)")
    )

    private val JUNK_PATTERNS = listOf(
        Regex("(?i)^read\\s+"),
        Regex("(?i)\\s+free\\s+online.*"),
        Regex("(?i)\\s+online\\s+free.*"),
        Regex("(?i)\\s*\\|\\s*.*$"),
        Regex("(?i)\\s+at\\s+.*"),
        Regex("(?i)[\\s–—\\-:]*(MangaBat|NovelFire|MangaPark|MangaKakalot).*$"),
        Regex("(?i)[\\s–—\\-:]*Scan.*$")
    )

    private val CHAPTER_MARKER_PATTERNS = listOf(
        Regex("[–—\\-:]?\\s*(?:chapter|ch|ch\\.)\\s*\\d+.*$", RegexOption.IGNORE_CASE),
        Regex("\\s*[–—\\-]\\s*\\d+.*$"),
        Regex("\\s*:\\s*\\d+.*$")
    )

    private val CLEAN_SEPARATORS_START_REGEX = Regex("^[\\s–—\\-:\\|]+")
    private val CLEAN_SEPARATORS_END_REGEX = Regex("[\\s–—\\-:\\|]+$")
    private val CLEAN_CHAPTER_TITLE_SUBTITLE_REGEX = Regex("(?i)(?:chapter|ch|ch\\.)\\s*\\d+[\\s:\\-—–|]+(.+)")

    private val CHAPTER_URL_REGEX = Regex("(\\d+)(?!.*\\d)")

    private val CHAPTER_NUMBER_REGEXES = listOf(
        Regex("chapter[\\s-_]*?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("ch[\\s-_]*?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("c[\\s-_]*?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("(\\d+(?:\\.\\d+)?)(?!.*\\d)", RegexOption.IGNORE_CASE)
    )

    private fun lastWord(s: String): String = TextHeuristics.lastWord(s)

    /**
     * Remove page numbers from text content.
     */
    fun removePageNumbers(text: String, isPdfContent: Boolean = false): String {
        if (text.isEmpty() || !isPdfContent) return text
        return text.lines().filterNot { it.trim().matches(DIGIT_REGEX) }.joinToString("\n")
    }

    /**
     * Remove "Page |" or "Page " prefix from text
     */
    fun removePageWord(text: String): String {
        return if (text.isEmpty()) text else text.replace(PAGE_WORD_REGEX, "")
    }

    /**
     * Increment the chapter number in a URL
     */
    fun incrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val match = CHAPTER_URL_REGEX.find(url)
        return if (match != null) {
            val group = match.groupValues[1]
            val num = group.toInt()
            url.replaceRange(match.range, (num + 1).toString())
        } else url
    }

    /**
     * Decrement the chapter number in a URL
     */
    fun decrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val match = CHAPTER_URL_REGEX.find(url)
        return if (match != null) {
            val group = match.groupValues[1]
            val num = group.toInt()
            if (num > 1) url.replaceRange(match.range, (num - 1).toString()) else url
        } else url
    }

    /**
     * Extract title from URL path
     */
    fun extractTitleFromUrl(url: String): String {
        if (url.isEmpty()) return "Unknown"
        return runCatching {
            val uri = URI(url)
            val lastSegment = uri.path.split("/").filter { it.isNotEmpty() }.lastOrNull()

            lastSegment?.let { segment ->
                segment.replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            } ?: uri.host ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    /**
     * Extract base title by removing chapter markers and common web junk.
     */
    fun extractBaseTitle(title: String, contentType: io.aatricks.easyreader.data.model.ContentType): String {
        if (contentType != io.aatricks.easyreader.data.model.ContentType.WEB) return title

        var normalized = removeCommonJunk(title)
        normalized = removeChapterMarkers(normalized)
        normalized = cleanSeparators(normalized)

        return if (normalized.isBlank() || normalized.length < 3) title else normalized
    }

    private fun removeCommonJunk(text: String): String {
        return JUNK_PATTERNS.fold(text) { acc, pattern -> acc.replace(pattern, "") }
    }

    private fun removeChapterMarkers(text: String): String {
        return CHAPTER_MARKER_PATTERNS.fold(text) { acc, pattern -> acc.replace(pattern, "").trim() }
    }

    private fun cleanSeparators(text: String): String {
        return text.replace(CLEAN_SEPARATORS_START_REGEX, "")
            .replace(CLEAN_SEPARATORS_END_REGEX, "")
            .trim()
    }

    /**
     * Extract a standardized chapter label from text.
     */
    fun extractChapterLabel(title: String?): String? {
        if (title.isNullOrBlank()) return null

        EXTRACT_CHAPTER_LABEL_REGEX_1.find(title)?.let {
            return "Chapter " + it.groupValues[1]
        }

        EXTRACT_CHAPTER_LABEL_REGEX_2.find(title)?.let {
            return "Chapter " + it.groupValues[1]
        }

        return EXTRACT_CHAPTER_LABEL_REGEX_3.findAll(title).lastOrNull()?.let {
            "Chapter " + it.groupValues[1]
        }
    }

    /**
     * Extract chapter label from URL
     */
    fun extractChapterLabelFromUrl(url: String): String? {
        return EXTRACT_CHAPTER_LABEL_URL_PATTERNS.firstNotNullOfOrNull { r ->
            r.find(url)?.groupValues?.get(1)?.let { "Chapter " + it }
        }
    }

    /**
     * Extract chapter number from URL or text
     */
    fun extractChapterNumber(text: String): Double? {
        if (text.isEmpty()) return null
        return CHAPTER_NUMBER_REGEXES.firstNotNullOfOrNull {
            r -> r.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }

    /**
     * Format text for better readability
     */
    fun formatText(text: String): String {
        return TextFormattingPipeline.formatText(text)
    }


    /**
     * Clean HTML entities from text
     */
    fun cleanHtmlEntities(text: String): String {
        if (text.isEmpty()) return text
        val replacements = mapOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…"
        )
        return replacements.entries.fold(text) { acc, (k, v) -> acc.replace(k, v) }
    }

    /**
     * Truncate text to max length
     */
    fun truncateText(text: String?, maxLength: Int = 100): String {
        if (text == null) return ""
        if (text.length <= maxLength) return text
        return text.substring(0, maxLength) + "..."
    }

    /**
     * Formats the text of a chapter
     */
    fun formatChapterText(text: String): String {
        return TextFormattingPipeline.formatChapterText(text)
    }

    fun addLineBreaks(text: String): String {
        return text.replace(MULTIPLE_SPACES_REGEX, "\n\n").trim()
    }

    fun countWords(text: String): Int {
        return text.split(WHITESPACE_REGEX).size
    }

    /**
     * Estimate reading time in minutes using a baseline 200 words per minute.
     */
    fun estimateReadingTime(text: String, wordsPerMinute: Int = 200): Int {
        val wpm = wordsPerMinute.coerceAtLeast(1)
        val words = countWords(text).coerceAtLeast(0)
        return kotlin.math.ceil(words / wpm.toDouble()).toInt().coerceAtLeast(1)
    }

    /**
     * Clean a chapter title by removing junk and the novel name.
     */
    fun cleanChapterTitle(fullTitle: String?, novelName: String): String {
        if (fullTitle.isNullOrBlank()) return ""
        var cleaned = removeCommonJunk(fullTitle)

        if (novelName.isNotBlank() && cleaned.contains(novelName, ignoreCase = true)) {
            cleaned = cleaned.replace(novelName, "", ignoreCase = true)
        }

        cleaned = cleanSeparators(cleaned)

        if (cleaned.length > 40 || cleaned.contains("Chapter", ignoreCase = true) || cleaned.contains(
                "Ch.",
                ignoreCase = true
            )
        ) {
            val label = extractChapterLabel(cleaned)
            if (label != null) {
                val subTitle = CLEAN_CHAPTER_TITLE_SUBTITLE_REGEX.find(cleaned)?.groupValues?.get(1)?.trim()
                return if (!subTitle.isNullOrBlank() && subTitle.length > 2) (label + ": " + subTitle) else label
            }
        }

        return if (cleaned.isBlank() || (novelName.isNotBlank() && fullTitle.equals(
                novelName,
                ignoreCase = true
            )))
            "" else cleaned
    }

    /**
     * Guess if the content should be in paged mode.
     */
    fun guessIsPaged(content: io.aatricks.easyreader.data.model.ChapterContent): Boolean {
        val imageCount = content.getImageCount()
        val textCount = content.getTextCount()
        if (textCount > imageCount * 2) return false
        return imageCount in 5..60 && textCount < 10
    }
}
