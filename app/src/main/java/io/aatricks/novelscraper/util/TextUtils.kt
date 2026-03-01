package io.aatricks.novelscraper.util

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
    private val LINE_BREAK_REGEX = Regex("\\r\\n|\\r")
    private val SPACE_PLUS_NEWLINE_REGEX = Regex(" +\\n")
    private val FOUR_PLUS_NEWLINES_REGEX = Regex("\\n{4,}")
    private val THREE_PLUS_NEWLINES_REGEX = Regex("\\n{3,}")
    private val PARAGRAPH_SPLIT_REGEX = Regex("(?s)(.*?)(\\n+|$)")
    private val LIST_MARKER_REGEX = Regex("^(?:\\d+|[ivxIVX]+\\.|[-*•])\\s")
    private val NEWLINE_BEFORE_LOWER_DIGIT_REGEX = Regex("\\n(?=[a-z0-9])")
    private val SINGLE_NEWLINE_REGEX = Regex("(?<!\\n)\\n(?!\\n)")
    private val TWO_PLUS_SPACES_REGEX = Regex("[ ]{2,}")

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
        Regex("(?i)\\s*|\\s*.*$"),
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

    private val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
    private val CONTINUATION_WORDS = setOf(
        "of",
        "to",
        "for",
        "and",
        "but",
        "or",
        "the",
        "a",
        "an",
        "my",
        "his",
        "her",
        "their",
        "its",
        "in",
        "on",
        "at",
        "from",
        "with"
    )

    private fun lastWord(s: String): String {
        return s.trim().split(WHITESPACE_REGEX).lastOrNull() ?: ""
    }

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
    fun extractBaseTitle(title: String, contentType: io.aatricks.novelscraper.data.model.ContentType): String {
        if (contentType != io.aatricks.novelscraper.data.model.ContentType.WEB) return title

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
        if (text.isEmpty()) return text

        var current = text
        current = replaceSpacesPlusNewline(current, ' ')
        current = replaceWindowsLineEndings(current)
        current = replaceSpacesPlusNewline(current, '\n')
        current = replaceFourPlusNewlines(current)
        return current.trim()
    }

    private fun replaceSpacesPlusNewline(text: String, replacementChar: Char): String {
        var i = 0
        val len = text.length
        var hasMatch = false
        while (i < len) {
            if (text[i] == ' ') {
                var j = i + 1
                while (j < len && text[j] == ' ') j++
                if (j < len && text[j] == '\n') {
                    hasMatch = true
                    break
                }
                i = j
            } else {
                i++
            }
        }
        if (!hasMatch) return text

        val sb = StringBuilder(len)
        i = 0
        while (i < len) {
            if (text[i] == ' ') {
                var j = i + 1
                while (j < len && text[j] == ' ') j++
                if (j < len && text[j] == '\n') {
                    sb.append(replacementChar)
                    i = j + 1
                } else {
                    for (k in i until j) sb.append(' ')
                    i = j
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun replaceWindowsLineEndings(text: String): String {
        var i = 0
        val len = text.length
        var hasMatch = false
        while (i < len) {
            if (text[i] == '\r') {
                hasMatch = true
                break
            }
            i++
        }
        if (!hasMatch) return text

        val sb = StringBuilder(len)
        i = 0
        while (i < len) {
            if (text[i] == '\r') {
                sb.append('\n')
                if (i + 1 < len && text[i + 1] == '\n') {
                    i += 2
                } else {
                    i += 1
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun replaceFourPlusNewlines(text: String): String {
        var i = 0
        val len = text.length
        var hasMatch = false
        while (i < len) {
            if (text[i] == '\n') {
                var count = 1
                var j = i + 1
                while (j < len && text[j] == '\n') {
                    count++
                    j++
                }
                if (count >= 4) {
                    hasMatch = true
                    break
                }
                i = j
            } else {
                i++
            }
        }
        if (!hasMatch) return text

        val sb = StringBuilder(len)
        i = 0
        while (i < len) {
            if (text[i] == '\n') {
                var count = 1
                var j = i + 1
                while (j < len && text[j] == '\n') {
                    count++
                    j++
                }
                if (count >= 4) {
                    sb.append("\\n\\n\\n")
                } else {
                    for (k in 0 until count) sb.append('\n')
                }
                i = j
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
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
     * Truncate text to a maximum length with ellipsis
     */
    fun truncate(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text else text.take(maxLength - 3) + "..."
    }

    /**
     * Count words in text
     */
    fun countWords(text: String): Int {
        return if (text.isEmpty()) 0 else text.trim().split(WHITESPACE_REGEX).size
    }

    /**
     * Estimate reading time in minutes
     */
    fun estimateReadingTime(text: String): Int {
        return maxOf(1, (countWords(text) / 200.0).toInt())
    }

    /**
     * Formats the text of a chapter
     */
    fun formatChapterText(text: String): String {
        if (text.isEmpty()) return text
        val normalized = text.trim().replace(LINE_BREAK_REGEX, "\n")

        val rawParagraphs = initialParagraphSplit(normalized)
        val initialMerged = mergeAccidentalSplits(rawParagraphs, normalized)
        val compacted = compactParagraphs(initialMerged, normalized)
        val processed = processIndividualParagraphs(compacted)

        return finalCollapse(processed, normalized)
    }

    private fun initialParagraphSplit(text: String): List<Pair<String, Int>> =
        PARAGRAPH_SPLIT_REGEX.findAll(text)
            .map { it.groupValues[1] to it.groupValues[2].length }
            .toList()

    private fun mergeAccidentalSplits(paragraphs: List<Pair<String, Int>>, original: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < paragraphs.size) {
            val p = paragraphs[i++]
            var cur = p.first.trim()
            val sepCount = p.second

            if (sepCount < 2 && i < paragraphs.size) {
                val next = paragraphs[i].first.trim()
                if (next.isNotEmpty() && shouldMerge(cur, next)) {
                    cur = (cur + " " + next).replace(MULTIPLE_SPACES_REGEX, " ")
                    i++

                    while (i < paragraphs.size) {
                        val peek = paragraphs[i].first.trim()
                        if (peek.isEmpty()) {
                            i++; continue
                        }
                        if (shouldStopGreedyMerge(cur, peek)) break
                        cur = (cur + " " + peek).replace(MULTIPLE_SPACES_REGEX, " ")
                        i++
                    }
                }
            }
            if (cur.isNotEmpty()) result.add(cur)
        }
        return result
    }

    private fun shouldMerge(cur: String, next: String): Boolean {
        val lastChar = cur.lastOrNull() ?: return false
        val lastW = lastWord(cur).lowercase()
        val wordCount = cur.split(WHITESPACE_REGEX).size

        return !SENTENCE_ENDERS.contains(lastChar) &&
                (wordCount <= 8 || lastW in CONTINUATION_WORDS || lastW.length <= 4) &&
                !isHeading(next) && !(cur.contains(':') && next.contains(':'))
    }

    private fun isHeading(text: String): Boolean {
        val firstChar = text.firstOrNull() ?: return false
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        val isAllUpper = text.uppercase() == text
        return firstChar.isUpperCase() && words.size in 1..4 && (isAllUpper || text.trimEnd().endsWith(":"))
    }

    private fun shouldStopGreedyMerge(cur: String, peek: String): Boolean {
        val peekFirst = peek.firstOrNull() ?: return true
        val lastChar = cur.trim().lastOrNull() ?: ' '
        return peekFirst.isUpperCase() && SENTENCE_ENDERS.contains(lastChar)
    }

    private fun compactParagraphs(paragraphs: List<String>, original: String): List<String> {
        val compacted = mutableListOf<String>()
        var pi = 0
        while (pi < paragraphs.size) {
            var cur = paragraphs[pi++].trim()

            while (pi < paragraphs.size) {
                val nxt = paragraphs[pi].trim()
                if (nxt.isEmpty()) {
                    pi++; continue
                }

                val shouldMerge = shouldMergeAggressive(cur, nxt) && !original.contains(cur + "\n\n" + nxt)
                if (shouldMerge) {
                    cur = (cur + " " + nxt).replace(MULTIPLE_SPACES_REGEX, " ")
                    pi++
                } else break
            }
            if (cur.isNotEmpty()) compacted.add(cur)
        }
        return compacted
    }

    private fun shouldMergeAggressive(cur: String, next: String): Boolean {
        val lastChar = cur.lastOrNull() ?: return false
        val lastW = lastWord(cur).lowercase()
        val wordCount = cur.split(WHITESPACE_REGEX).size
        val isSentenceEnd = SENTENCE_ENDERS.contains(lastChar)

        return !isSentenceEnd &&
                (wordCount <= 10 || lastW in CONTINUATION_WORDS || lastW.length <= 4) &&
                !isHeading(next) && !(cur.contains(':') && next.contains(':'))
    }

    private fun processIndividualParagraphs(paragraphs: List<String>): List<String> {
        return paragraphs.map {
            if (it.trim().isEmpty()) return@map ""

            val lines = it.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size <= 1) return@map it.trim()

            val sb = StringBuilder(lines[0])
            for (i in 1 until lines.size) {
                val prevLine = lines[i - 1]
                val curLine = lines[i]
                val lastChar = prevLine.lastOrNull() ?: ' '

                if (SENTENCE_ENDERS.contains(lastChar) || isHeading(curLine) || LIST_MARKER_REGEX.containsMatchIn(
                        curLine
                    )
                ) {
                    sb.append("\n\n").append(curLine)
                } else {
                    sb.append(" ").append(curLine)
                }
            }
            var result = sb.toString()
            result = result.replace(MULTIPLE_SPACES_REGEX, " ")
            result
        }
    }

    private fun finalCollapse(processed: List<String>, original: String): String {
        val joined = processed.joinToString("\n\n").replace(THREE_PLUS_NEWLINES_REGEX, "\n\n")
        val parts = joined.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        collapseContinuationParagraphs(parts, original)

        var result = parts.joinToString("\n\n")
        result = result.replace(SINGLE_NEWLINE_REGEX, " ")
        result = result.replace(TWO_PLUS_SPACES_REGEX, " ")
        return result.trim()
    }

    private fun collapseContinuationParagraphs(parts: MutableList<String>, original: String): Unit {
        var i = 0
        while (i < parts.size - 1) {
            val left = parts[i]
            val right = parts[i + 1]
            if (original.contains(left + "\n\n" + right)) {
                i++; continue
            }

            val leftLast = left.lastOrNull() ?: ' '
            val rightFirst = right.firstOrNull() ?: ' '

            val shouldCollapse = !SENTENCE_ENDERS.contains(leftLast) &&
                    (rightFirst.isLowerCase() || rightFirst.isDigit())

            if (shouldCollapse) {
                parts[i] = left + " " + right
                parts[i] = parts[i].replace(MULTIPLE_SPACES_REGEX, " ")
                parts.removeAt(i + 1)
            } else i++
        }
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
    fun guessIsPaged(content: io.aatricks.novelscraper.data.model.ChapterContent): Boolean {
        val imageCount = content.getImageCount()
        val textCount = content.getTextCount()
        if (textCount > imageCount * 2) return false
        return imageCount in 5..60 && textCount < 10
    }
}
