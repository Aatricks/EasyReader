package io.aatricks.novelscraper.util

import java.net.URI
import java.util.regex.Pattern

/**
 * Utility functions for text processing and manipulation.
 * Handles text formatting, page number removal, and URL manipulation for chapter navigation.
 */
object TextUtils {

    // Pre-compiled Regex patterns for performance
    private val DIGIT_REGEX = Regex("\\d+")
    private val PAGE_WORD_REGEX = Regex("Page \\|\\s*|Page\\s+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MULTIPLE_SPACES_REGEX = Regex(" +")
    private val LINE_BREAK_REGEX = Regex("\\r\\n|\\r")
    private val SPACE_PLUS_NEWLINE_REGEX = Regex(" +\n")
    private val FOUR_PLUS_NEWLINES_REGEX = Regex("\n{4,}")
    private val THREE_PLUS_NEWLINES_REGEX = Regex("\n{3,}")
    private val PARAGRAPH_SPLIT_REGEX = Regex("(?s)(.*?)(\\n{2,}|$)")
    private val LIST_MARKER_REGEX = Regex("^(\\d+\\.|[ivxIVX]+\\.|[-*•])\\s")
    private val NEWLINE_BEFORE_LOWER_DIGIT_REGEX = Regex("\\n(?=[a-z0-9])")
    private val SINGLE_NEWLINE_REGEX = Regex("(?<!\\n)\\n(?!\\n)")
    private val TWO_PLUS_SPACES_REGEX = Regex("[ ]{2,}")

    private val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
    private val CONTINUATION_WORDS = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with")

    private fun lastWord(s: String): String {
        val parts = s.trim().split(WHITESPACE_REGEX)
        return parts.lastOrNull() ?: ""
    }

    // Pre-compiled Patterns for repetitive usage
    private val CHAPTER_URL_PATTERN = Pattern.compile("(\\d+)(?!.*\\d)")

    private val CHAPTER_NUMBER_PATTERNS = listOf(
        Pattern.compile("chapter[\\s-_]*?(\\d+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ch[\\s-_]*?(\\d+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("c[\\s-_]*?(\\d+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d+)(?!.*\\d)", Pattern.CASE_INSENSITIVE)
    )

    /**
     * Remove page numbers from text content.
     */
    fun removePageNumbers(text: String, isPdfContent: Boolean = false): String {
        if (text.isEmpty()) return text
        if (!isPdfContent) return text

        // Simplified logic: remove lines that are just numbers
        return text.lines().filterNot { it.trim().matches(DIGIT_REGEX) }.joinToString("\n")
    }

    /**
     * Remove "Page |" or "Page " prefix from text
     */
    fun removePageWord(text: String): String {
        if (text.isEmpty()) return text
        return text.replace(PAGE_WORD_REGEX, "")
    }

    /**
     * Increment the chapter number in a URL
     */
    fun incrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val matcher = CHAPTER_URL_PATTERN.matcher(url)
        return if (matcher.find()) {
            val group = matcher.group(1) ?: return url
            val num = group.toInt()
            matcher.replaceFirst((num + 1).toString())
        } else url
    }

    /**
     * Decrement the chapter number in a URL
     */
    fun decrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val matcher = CHAPTER_URL_PATTERN.matcher(url)
        return if (matcher.find()) {
            val group = matcher.group(1) ?: return url
            val num = group.toInt()
            if (num > 1) matcher.replaceFirst((num - 1).toString()) else url
        } else url
    }
    /**
     * Extract title from URL path
     * Gets the last non-empty path segment and formats it
     *
     * @param url The URL to extract title from
     * @return Extracted and formatted title
     */
    fun extractTitleFromUrl(url: String): String {
        if (url.isEmpty()) return "Unknown"
        
        return try {
            val uri = URI(url)
            val path = uri.path
            val pathSegments = path.split("/").filter { it.isNotEmpty() }
            
            // Get the last non-empty segment
            val lastSegment = pathSegments.lastOrNull()
            
            if (lastSegment != null) {
                // Replace hyphens and underscores with spaces
                // Capitalize first letter of each word
                lastSegment
                    .replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
            } else {
                uri.host ?: "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Extract base title by removing chapter markers and common web junk.
     * Only normalizes WEB content - PDFs/HTML/EPUB keep full titles.
     */
    fun extractBaseTitle(title: String, contentType: io.aatricks.novelscraper.data.model.ContentType): String {
        // Only normalize WEB content for grouping
        if (contentType != io.aatricks.novelscraper.data.model.ContentType.WEB) return title
        
        var normalized = title

        // 1. Remove common web novel "junk" first
        val junkPatterns = listOf(
            Regex("""(?i)^read\s+"""),
            Regex("""(?i)\s+free\s+online.*$"""),
            Regex("""(?i)\s+online\s+free.*$"""),
            Regex("""(?i)\s*\|\s*.*$"""), // Remove anything after |
            Regex("""(?i)\s+at\s+.*$"""), // Remove " at SourceName"
            Regex("""(?i)[\s–—\-:]*(MangaBat|NovelFire|MangaPark|MangaKakalot).*$"""),
            Regex("""(?i)[\s–—\-:]*Scan.*$""")
        )
        
        for (pattern in junkPatterns) {
            normalized = normalized.replace(pattern, "")
        }
        
        // 2. Remove common chapter markers and trailing content
        val chapterPatterns = listOf(
            Regex("""[–—\-:]?\s*(?:chapter|ch|ch\.)\s*\d+.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[–—\-]\s*\d+.*$"""), // "Title - 123" or "Title – 123"
            Regex("""\s*:\s*\d+.*$""") // "Title: 123"
        )
        for (pattern in chapterPatterns) {
            normalized = normalized.replace(pattern, "").trim()
        }

        // 3. Final cleanup of separators
        normalized = normalized.replace(Regex("""^[\s–—\-:\|]+"""), "")
            .replace(Regex("""[\s–—\-:\|]+$"""), "")
            .trim()

        return if (normalized.isBlank() || normalized.length < 3) title else normalized
    }

    /**
     * Extract a standardized chapter label (e.g., "Chapter 233") from text.
     */
    fun extractChapterLabel(title: String?): String? {
        if (title == null || title.isBlank()) return null
        
        // Priority 1: Explicit chapter markers
        val regex = Regex("""(?i)(?:chapter|ch|ch\.|c)\s*(\d+)""")
        val match = regex.find(title)
        if (match != null) {
            return "Chapter ${match.groupValues[1]}"
        }
        
        // Priority 2: Number after a separator at the end of the string
        // e.g. "Novel Title: 150" or "Novel Title - 150"
        val endNumberRegex = Regex("""[\s:\-—–\|](\d+)\s*$""")
        val endMatch = endNumberRegex.find(title)
        if (endMatch != null) {
            return "Chapter ${endMatch.groupValues[1]}"
        }
        
        // Priority 3: Any standalone number that isn't part of the title's year or volume
        // We look for the last number in the string as it's most likely the chapter
        val allNumbers = Regex("""\b(\d+)\b""").findAll(title)
        val lastNumberMatch = allNumbers.lastOrNull()
        if (lastNumberMatch != null) {
            val num = lastNumberMatch.groupValues[1]
            // Heuristic: chapter numbers are usually not years (like 2023) unless the novel is very long
            // and usually not single digits if there's a better match, but we just take the last one.
            return "Chapter $num"
        }
        
        return null
    }

    /**
     * Extract chapter label from URL
     */
    fun extractChapterLabelFromUrl(url: String): String? {
        val patterns = listOf(
            Regex("chapter\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("ch(?:apter)?\\D*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("/(\\d+)(?:/|$)"),
            Regex("-(\\d+)(?:\\D|$)")
        )
        for (r in patterns) {
            val m = r.find(url)
            if (m != null && m.groupValues.size >= 2) {
                val num = m.groupValues[1]
                return "Chapter $num"
            }
        }
        return null
    }

    /**
     * Extract chapter number from URL or text
     * 
     * @param text The text or URL to extract chapter from
     * @return Chapter number or null if not found
     */
    fun extractChapterNumber(text: String): Int? {
        if (text.isEmpty()) return null
        
        // Try to find chapter number with various patterns
        for (pattern in CHAPTER_NUMBER_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull()
            }
        }
        
        return null
    }

    /**
     * Format text for better readability
     * - Removes extra whitespace
     * - Normalizes line breaks
     * - Ensures proper paragraph spacing
     * 
     * @param text The text to format
     * @return Formatted text
     */
    fun formatText(text: String): String {
        if (text.isEmpty()) return text
        
        return text
            // Remove multiple spaces
            .replace(MULTIPLE_SPACES_REGEX, " ")
            // Normalize line breaks
            .replace(LINE_BREAK_REGEX, "\n")
            // Remove spaces at line ends
            .replace(SPACE_PLUS_NEWLINE_REGEX, "\n")
            // Remove multiple consecutive newlines (keep max 3 for paragraph breaks)
            .replace(FOUR_PLUS_NEWLINES_REGEX, "\\n\\n\\n")
            .trim()
    }

    /**
     * Clean HTML entities from text
     * 
     * @param text The text containing HTML entities
     * @return Text with entities decoded
     */
    fun cleanHtmlEntities(text: String): String {
        if (text.isEmpty()) return text
        
        var result = text
        result = result.replace("&nbsp;", " ")
        result = result.replace("&amp;", "&")
        result = result.replace("&lt;", "<")
        result = result.replace("&gt;", ">")
        result = result.replace("&quot;", "\"")
        result = result.replace("&#39;", "'")
        result = result.replace("&mdash;", "—")
        result = result.replace("&ndash;", "–")
        result = result.replace("&hellip;", "…")
        return result
    }

    /**
     * Truncate text to a maximum length with ellipsis
     * 
     * @param text The text to truncate
     * @param maxLength Maximum length (including ellipsis)
     * @return Truncated text
     */
    fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength - 3) + "..."
    }

    /**
     * Count words in text
     * 
     * @param text The text to count words in
     * @return Word count
     */
    fun countWords(text: String): Int {
        if (text.isEmpty()) return 0
        return text.trim().split(WHITESPACE_REGEX).size
    }

    /**
     * Estimate reading time in minutes
     * Based on average reading speed of 200 words per minute
     * 
     * @param text The text to estimate reading time for
     * @return Estimated reading time in minutes
     */
    fun estimateReadingTime(text: String): Int {
        val wordCount = countWords(text)
        return maxOf(1, (wordCount / 200.0).toInt())
    }

    /**
     * Formats the text of a chapter by removing extra whitespace and normalizing paragraph breaks.
     */
    fun formatChapterText(text: String): String {
        if (text.isEmpty()) return text

        val normalized = text.trim().replace(LINE_BREAK_REGEX, "\n")

        val rawParagraphsWithSep = PARAGRAPH_SPLIT_REGEX.findAll(normalized)
            .map { it.groupValues[1] to it.groupValues[2].length }
            .toList()
        var rawParagraphs = rawParagraphsWithSep.map { it.first }

        // Initial merge of accidental splits
        val paragraphs = mutableListOf<String>()
        var i = 0
        while (i < rawParagraphs.size) {
            var cur = rawParagraphs[i].trim()
            if (cur.isEmpty()) { i++; continue }

            if (i + 1 < rawParagraphs.size) {
                val next = rawParagraphs[i + 1].trim()
                if (rawParagraphsWithSep[i].second >= 2) {
                    paragraphs.add(cur)
                    i++
                    continue
                }
                if (next.isNotEmpty()) {
                    val lastChar = cur.lastOrNull()
                    val lastW = lastWord(cur).lowercase()
                    val wordCount = cur.split(WHITESPACE_REGEX).size

                    val shouldMerge = (lastChar != null && !SENTENCE_ENDERS.contains(lastChar)) &&
                            (wordCount <= 8 || lastW in CONTINUATION_WORDS || lastW.length <= 4) &&
                            !(cur.contains(':') && next.contains(':'))

                    val nextFirstChar = next.firstOrNull()
                    val nextWordCount = next.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size
                    val looksLikeHeading = nextFirstChar != null && nextFirstChar.isUpperCase() && nextWordCount in 1..4 &&
                        (next.uppercase() == next || next.trimEnd().endsWith(":"))

                    if (shouldMerge && !looksLikeHeading) {
                        cur = (cur + " " + next).replace(MULTIPLE_SPACES_REGEX, " ")
                        i += 2
                        while (i < rawParagraphs.size) {
                            val peek = rawParagraphs[i].trim()
                            if (peek.isEmpty()) { i++; continue }
                            val peekFirst = peek.firstOrNull()
                            if (peekFirst != null && peekFirst.isUpperCase() && cur.trim().lastOrNull()?.let { SENTENCE_ENDERS.contains(it) } == true) break
                            cur = (cur + " " + peek).replace(MULTIPLE_SPACES_REGEX, " ")
                            i++
                        }
                        paragraphs.add(cur)
                        continue
                    }
                }
            }
            paragraphs.add(cur)
            i++
        }

        // Conservative aggressive merge
        val compacted = mutableListOf<String>()
        var pi = 0
        while (pi < paragraphs.size) {
            var cur = paragraphs[pi].trim()
            if (cur.isEmpty()) { pi++; continue }

            while (pi + 1 < paragraphs.size) {
                val nxt = paragraphs[pi + 1].trim()
                if (nxt.isEmpty()) { pi++; continue }

                if (normalized.contains(cur + "\n\n" + nxt)) break

                val lastChar = cur.lastOrNull()
                val lastW = lastWord(cur).lowercase()
                val wordCount = cur.split(WHITESPACE_REGEX).size
                val nextFirst = nxt.firstOrNull()
                val nextWordCountAgg = nxt.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size
                val looksLikeHeadingAgg = nextFirst != null && nextFirst.isUpperCase() && nextWordCountAgg in 1..4 &&
                    (nxt.uppercase() == nxt || nxt.trimEnd().endsWith(":"))

                val shouldMergeAggressive = (lastChar != null && !SENTENCE_ENDERS.contains(lastChar)) &&
                    (wordCount <= 10 || lastW in CONTINUATION_WORDS || lastW.length <= 4) && !looksLikeHeadingAgg &&
                    !(cur.contains(':') && nxt.contains(':'))

                if (shouldMergeAggressive) {
                    cur = (cur + " " + nxt).replace(MULTIPLE_SPACES_REGEX, " ")
                    pi++
                    continue
                }
                break
            }
            compacted.add(cur)
            pi++
        }

        val processedParagraphs = compacted.map { paragraph ->
            val p = paragraph.trim()
            if (p.isEmpty()) return@map ""

            var builder = StringBuilder(p.replace(MULTIPLE_SPACES_REGEX, " "))
            var j = 0
            while (j < builder.length) {
                val c = builder[j]
                if (c == '\n') {
                    var prevIndex = j - 1
                    while (prevIndex >= 0 && builder[prevIndex].isWhitespace()) prevIndex--
                    val prevChar = if (prevIndex >= 0) builder[prevIndex] else null

                    var nextIndex = j + 1
                    while (nextIndex < builder.length && builder[nextIndex].isWhitespace()) nextIndex++
                    val nextChar = if (nextIndex < builder.length) builder[nextIndex] else null

                    val nextLineEnd = builder.indexOf('\n', nextIndex).let { if (it == -1) builder.length else it }
                    val nextLineSnippet = if (nextIndex < builder.length) builder.substring(nextIndex, minOf(nextLineEnd, nextIndex + 60)).trimStart() else ""

                    val startsWithQuoteOrDash = nextLineSnippet.startsWith("\"") || nextLineSnippet.startsWith("“") ||
                            nextLineSnippet.startsWith("—") || nextLineSnippet.startsWith("-") || nextLineSnippet.startsWith("'")
                    val nextLineWords = nextLineSnippet.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size
                    val looksLikeHeading = nextLineWords in 1..4 && nextLineSnippet.firstOrNull()?.isUpperCase() == true &&
                        (nextLineSnippet.uppercase() == nextLineSnippet || nextLineSnippet.trimEnd().endsWith(":"))
                    val preserveBecauseNextLine = LIST_MARKER_REGEX.containsMatchIn(nextLineSnippet) || startsWithQuoteOrDash || looksLikeHeading

                    when {
                        prevChar == null -> {
                            builder.deleteCharAt(j)
                            continue
                        }
                        prevChar == '-' -> {
                            builder.deleteCharAt(j)
                            builder.deleteCharAt(prevIndex)
                            j = maxOf(0, prevIndex)
                            continue
                        }
                        SENTENCE_ENDERS.contains(prevChar) || preserveBecauseNextLine -> {
                            var k = j - 1
                            while (k >= 0 && builder[k].isWhitespace()) { builder.deleteCharAt(k); k--; j-- }
                            var m = j + 1
                            while (m < builder.length && builder[m].isWhitespace()) { builder.deleteCharAt(m) }
                            if (j >= builder.length || builder[j] != '\n') continue
                            j++
                            continue
                        }
                        else -> {
                            builder.deleteCharAt(j)
                            val pChar = prevChar!!
                            val needSpace = !pChar.isWhitespace() && pChar != '-' &&
                                            (nextChar != null && !nextChar.isWhitespace() && nextChar != ',' && nextChar != '.')
                            if (needSpace) {
                                builder.insert(j, ' ')
                                j++
                            }
                            continue
                        }
                    }
                }
                j++
            }
            builder.toString().replace(NEWLINE_BEFORE_LOWER_DIGIT_REGEX, " ")
        }

        val joined = processedParagraphs.joinToString("\n\n").replace(THREE_PLUS_NEWLINES_REGEX, "\n\n")

        val parts = joined.split("\n\n").map { it.trim() }.toMutableList()
        var pi2 = 0
        while (pi2 < parts.size - 1) {
            val left = parts[pi2]
            val right = parts[pi2 + 1]
            if (left.isEmpty() || right.isEmpty()) { pi2++; continue }

            if (normalized.contains(left + "\n\n" + right)) { pi2++; continue }

            val lastChar = left.lastOrNull()
            val lastW = lastWord(left).lowercase()
            val leftWordCount = left.split(WHITESPACE_REGEX).size
            val continuationWords2 = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an")

            val shouldCollapseParagraph = (lastChar != null && !SENTENCE_ENDERS.contains(lastChar)) &&
                    (leftWordCount <= 10 || lastW in continuationWords2 || lastW.length <= 4)

            if (shouldCollapseParagraph) {
                parts[pi2] = (left + " " + right).replace(MULTIPLE_SPACES_REGEX, " ")
                parts.removeAt(pi2 + 1)
            } else {
                pi2++
            }
        }

        val collapsed = parts.joinToString("\n\n")

        val postParts = collapsed.split("\n\n").map { it.trim() }.toMutableList()
        var idx = 0
        while (idx < postParts.size - 1) {
            val left = postParts[idx]
            val right = postParts[idx + 1]
            if (left.isEmpty() || right.isEmpty()) { idx++; continue }

            if (normalized.contains(left + "\n\n" + right)) { idx++; continue }

            val leftLast = left.lastOrNull()
            val rightFirst = right.firstOrNull()
            val shouldMergeBecauseRightIsContinuation = (rightFirst != null && (rightFirst.isLowerCase() || rightFirst.isDigit())) &&
                    (leftLast == null || !SENTENCE_ENDERS.contains(leftLast))

            if (shouldMergeBecauseRightIsContinuation) {
                postParts[idx] = (left + " " + right).replace(MULTIPLE_SPACES_REGEX, " ")
                postParts.removeAt(idx + 1)
            } else {
                idx++
            }
        }

        var finallyCollapsed = postParts.joinToString("\n\n")
        val collapsedSingleNewlines = finallyCollapsed.replace(SINGLE_NEWLINE_REGEX, " ")
        return collapsedSingleNewlines.replace(TWO_PLUS_SPACES_REGEX, " ").trim()
    }

    /**
     * Debug helper used in tests to inspect how paragraphs are split.
     */
    /**
     * Clean a chapter title by removing junk and the novel name.
     */
    fun cleanChapterTitle(fullTitle: String?, novelName: String): String {
        if (fullTitle == null || fullTitle.isBlank()) return ""
        var cleaned: String = fullTitle
        val junkPatterns = listOf(
            Regex("""(?i)^read\s+"""),
            Regex("""(?i)\s+free\s+online.*$"""),
            Regex("""(?i)\s+online\s+free.*$"""),
            Regex("""(?i)\s*\|\s*.*$"""), 
            Regex("""(?i)\s+at\s+.*$"""), 
            Regex("""(?i)[\s–—\-:]*(MangaBat|NovelFire|MangaPark|MangaKakalot).*$"""),
            Regex("""(?i)[\s–—\-:]*Scan.*$""")
        )
        for (pattern in junkPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        if (novelName.isNotBlank()) {
            if (cleaned.contains(novelName, ignoreCase = true)) {
                cleaned = cleaned.replace(novelName, "", ignoreCase = true)
            }
        }
        cleaned = cleaned.replace(Regex("""^[\s–—\-:\|]+"""), "")
            .replace(Regex("""[\s–—\-:\|]+$"""), "")
            .trim()
        if (cleaned.length > 40 || cleaned.contains("Chapter", ignoreCase = true) || cleaned.contains("Ch.", ignoreCase = true)) {
             val extractedLabel = extractChapterLabel(cleaned)
             if (extractedLabel != null) {
                 val subTitleRegex = Regex("""(?i)(?:chapter|ch|ch\.)\s*\d+[\s:\-—–\|]+(.+)""")
                 val match = subTitleRegex.find(cleaned)
                 val subTitle = match?.groupValues?.get(1)?.trim()
                 return if (!subTitle.isNullOrBlank() && subTitle.length > 2) {
                     "$extractedLabel: $subTitle"
                 } else {
                     extractedLabel
                 }
             }
        }
        if (cleaned.isBlank() || (novelName.isNotBlank() && fullTitle.equals(novelName, ignoreCase = true))) {
            return ""
        }
        return cleaned
    }

    /**
     * Guess if the content should be in paged mode based on image vs text count.
     */
    fun guessIsPaged(content: io.aatricks.novelscraper.data.model.ChapterContent): Boolean {
        val imageCount = content.getImageCount()
        val textCount = content.getTextCount()
        if (textCount > imageCount * 2) return false
        if (imageCount > 0) {
            if (imageCount in 5..60 && textCount < 10) return true
            if (imageCount > 60) return false
        }
        return false
    }
}