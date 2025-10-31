package io.aatricks.novelscraper.util

import android.net.Uri
import java.util.regex.Pattern

/**
 * Utility functions for text processing and manipulation.
 * Handles text formatting, page number removal, and URL manipulation for chapter navigation.
 */
object TextUtils {

    /**
     * Remove page numbers from text content.
     * Based on the logic from the Java app's getStringBuilder method.
     * 
     * @param text The text to process
     * @param isPdfContent Whether the content is from a PDF (more aggressive filtering)
     * @return Processed text with page numbers removed
     */
    fun removePageNumbers(text: String, isPdfContent: Boolean = false): String {
        if (text.isEmpty()) return text

        val result = StringBuilder()
        val seenNumbers = mutableSetOf<Int>()
        var i = 0

        while (i < text.length) {
            val char = text[i]
            result.append(char)

            // Handle punctuation combinations
            if (i > 1) {
                val prevChar = text[i - 1]
                val prevPrevChar = text[i - 2]

                // Remove comma after period or quote
                if (char == ',' && (prevChar == '.' || prevChar == '"')) {
                    result.deleteCharAt(result.length - 1)
                }

                // Handle newlines
                if (char == '\n') {
                    // Add paragraph breaks after sentences
                    if (prevChar == '.' || prevChar == '"' || prevPrevChar == '.' || prevPrevChar == '"') {
                        result.append("\n\n\n")
                    }
                    // Remove space before newline
                    if (prevChar == ' ') {
                        result.deleteCharAt(result.length - 2)
                    }
                    // Convert single newlines to spaces for better flow
                    result.deleteCharAt(result.length - 1)
                    result.append(" ")
                }

                // Handle potential page numbers (only for PDF content)
                if (char.isDigit() && isPdfContent) {
                    val numStartPos = i
                    var fullNumber = char.digitToInt()
                    var digitCount = 1
                    var hasComma = false
                    var hasPeriod = false

                    // Check if number is in brackets/parentheses
                    val isInBrackets = if (numStartPos > 0) {
                        val prevC = text[numStartPos - 1]
                        prevC in "([{<\"'"
                    } else false

                    // Collect the complete number
                    val numberStr = StringBuilder().append(char)
                    while (i + 1 < text.length && 
                           (text[i + 1].isDigit() || text[i + 1] == ',' || text[i + 1] == '.')) {
                        i++
                        val nextChar = text[i]
                        numberStr.append(nextChar)

                        when (nextChar) {
                            ',' -> hasComma = true
                            '.' -> hasPeriod = true
                            else -> {
                                fullNumber = fullNumber * 10 + nextChar.digitToInt()
                                digitCount++
                            }
                        }
                    }

                    // Check for ordinal suffixes
                    val hasOrdinalSuffix = if (i + 2 < text.length) {
                        val suffix = text.substring(i + 1, minOf(i + 3, text.length))
                        suffix.startsWith("th") || suffix.startsWith("st") || 
                        suffix.startsWith("nd") || suffix.startsWith("rd")
                    } else false

                    // Check for textual context
                    val inTextualContext = if (numStartPos > 1) {
                        val preceding = text.substring(maxOf(0, numStartPos - 10), numStartPos)
                            .lowercase().trim()
                        preceding.endsWith("the ") || preceding.endsWith("at ") || 
                        preceding.endsWith("to ") || preceding.endsWith("level ") || 
                        preceding.endsWith("floor ")
                    } else false

                    // Check for closing brackets
                    val hasClosingBracket = if (i + 1 < text.length) {
                        text[i + 1] in ")]}>\"'"
                    } else false

                    // Check for operators
                    val hasOperator = if (numStartPos > 0 || i + 1 < text.length) {
                        val operators = setOf('+', '-', '×', 'x', '*', '/', '=', '÷')
                        (numStartPos > 0 && text[numStartPos - 1] in operators) ||
                        (i + 1 < text.length && text[i + 1] in operators)
                    } else false

                    // Determine if should preserve
                    val shouldPreserve = hasComma || 
                                        (hasPeriod && i + 1 < text.length && text[i + 1].isDigit()) ||
                                        hasOrdinalSuffix || inTextualContext ||
                                        (isInBrackets && hasClosingBracket) || hasOperator

                    // Check if it's a reasonable page number
                    val isReasonablePageNumber = fullNumber in 1..999
                    val leftBoundary = numStartPos == 0 || !text[numStartPos - 1].isLetterOrDigit()
                    val rightBoundary = i == text.length - 1 || 
                                       (!text[i + 1].isLetterOrDigit() && !hasOrdinalSuffix)

                    val isPossiblePageNumber = isReasonablePageNumber && 
                                              leftBoundary && rightBoundary && 
                                              !shouldPreserve

                    if (isPossiblePageNumber && fullNumber !in seenNumbers) {
                        // Remove the number
                        repeat(numberStr.length) {
                            if (result.isNotEmpty()) {
                                result.deleteCharAt(result.length - 1)
                            }
                        }
                        seenNumbers.add(fullNumber)
                    }
                }
            }
            i++
        }

        return result.toString()
    }

    /**
     * Remove "Page |" or "Page " prefix from text
     * 
     * @param text The text to process
     * @return Text with page prefixes removed
     */
    fun removePageWord(text: String): String {
        if (text.isEmpty()) return text
        val regex = "(Page \\||Page )"
        val pattern = Pattern.compile(regex)
        val matcher = pattern.matcher(text)
        return matcher.replaceAll("")
    }

    /**
     * Increment the chapter number in a URL
     * Finds the last number in the URL and increments it by 1
     * 
     * @param url The URL to process
     * @return URL with incremented chapter number
     */
    fun incrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        
        // Pattern to find the last number in the URL
        val pattern = Pattern.compile("(\\d+)(?!.*\\d)")
        val matcher = pattern.matcher(url)
        
        return if (matcher.find()) {
            val chapterNumber = matcher.group(1)?.toIntOrNull() ?: return url
            val incrementedNumber = chapterNumber + 1
            matcher.replaceFirst(incrementedNumber.toString())
        } else {
            url
        }
    }

    /**
     * Decrement the chapter number in a URL
     * Finds the last number in the URL and decrements it by 1
     * Won't go below 1
     * 
     * @param url The URL to process
     * @return URL with decremented chapter number
     */
    fun decrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        
        val pattern = Pattern.compile("(\\d+)(?!.*\\d)")
        val matcher = pattern.matcher(url)
        
        return if (matcher.find()) {
            val chapterNumber = matcher.group(1)?.toIntOrNull() ?: return url
            if (chapterNumber > 1) {
                val decrementedNumber = chapterNumber - 1
                matcher.replaceFirst(decrementedNumber.toString())
            } else {
                url // Don't go below chapter 1
            }
        } else {
            url
        }
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
            val uri = Uri.parse(url)
            val pathSegments = uri.pathSegments
            
            // Get the last non-empty segment
            val lastSegment = pathSegments.lastOrNull { it.isNotEmpty() }
            
            if (lastSegment != null) {
                // Replace hyphens and underscores with spaces
                // Capitalize first letter of each word
                lastSegment
                    .replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
            } else {
                uri.host ?: "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
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
        val patterns = listOf(
            "chapter[\\s-_]*?(\\d+)",
            "ch[\\s-_]*?(\\d+)",
            "c[\\s-_]*?(\\d+)",
            "(\\d+)(?!.*\\d)" // Last number in text
        )
        
        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
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
            .replace(Regex(" +"), " ")
            // Normalize line breaks
            .replace(Regex("\\r\\n|\\r"), "\n")
            // Remove spaces at line ends
            .replace(Regex(" +\n"), "\n")
            // Remove multiple consecutive newlines (keep max 3 for paragraph breaks)
            .replace(Regex("\n{4,}"), "\n\n\n")
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
        
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
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
        return text.trim().split(Regex("\\s+")).size
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
     *
     * @param text The chapter text to format.
     * @return The formatted chapter text.
     */
    fun formatChapterText(text: String): String {
        if (text.isEmpty()) return text

        // Normalize line endings first
        val normalized = text.trim().replace(Regex("\\r\\n|\\r"), "\n")

        // Split into paragraphs on 2+ newlines.
        var rawParagraphs = normalized.split(Regex("\\n{2,}"))

        // Keep all raw paragraphs; do not remove promotional/footer fragments here.

        // Merge adjacent paragraphs that look like accidental splits (e.g., line-wrap
        // produced an extra blank line between a sentence continuation). Heuristics:
        // - If previous paragraph does not end with a sentence terminator and
        //   either the previous paragraph is short (<= 8 words) or ends with a small
        //   'continuation' word (of, to, for, etc.), then merge with next paragraph.
        val paragraphs = mutableListOf<String>()
        var i = 0
        while (i < rawParagraphs.size) {
            var cur = rawParagraphs[i].trim()
            if (cur.isEmpty()) { i++; continue }

            // Lookahead to decide if we should merge with next paragraph
            if (i + 1 < rawParagraphs.size) {
                val next = rawParagraphs[i + 1].trim()
                if (next.isNotEmpty()) {
                    val lastChar = cur.lastOrNull()
                    val sentenceEnders = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')

                    fun lastWord(s: String): String {
                        val parts = s.trim().split(Regex("\\s+"))
                        return parts.lastOrNull() ?: ""
                    }

                    val continuationWords = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with")
                    val lastW = lastWord(cur).lowercase()
                    val wordCount = cur.split(Regex("\\s+")).size

                    val shouldMerge = (lastChar != null && !sentenceEnders.contains(lastChar)) &&
                            (wordCount <= 8 || lastW in continuationWords || lastW.length <= 4)

                    if (shouldMerge) {
                        // Merge current and next paragraph with a space (preserve spacing)
                        cur = (cur + " " + next).replace(Regex(" +"), " ")
                        i += 2
                        // In case there are multiple accidental splits, keep merging
                        while (i < rawParagraphs.size) {
                            val peek = rawParagraphs[i].trim()
                            if (peek.isEmpty()) { i++; continue }
                            // stop merging if peek looks like a proper paragraph start (starts with uppercase and current ends with sentence end)
                            val peekFirst = peek.firstOrNull()
                            if (peekFirst != null && peekFirst.isUpperCase() && cur.trim().lastOrNull()?.let { sentenceEnders.contains(it) } == true) break
                            cur = (cur + " " + peek).replace(Regex(" +"), " ")
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

        // Second, conservative pass: merge any remaining adjacent paragraphs that
        // still look like accidental splits. This helps catch cases where the
        // initial heuristics missed short fragments like "No sense" + "of honor.".
        val sentenceEnders = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
        val continuationWords = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with")

        val compacted = mutableListOf<String>()
        var pi = 0
        while (pi < paragraphs.size) {
            var cur = paragraphs[pi].trim()
            if (cur.isEmpty()) { pi++; continue }

            while (pi + 1 < paragraphs.size) {
                val nxt = paragraphs[pi + 1].trim()
                if (nxt.isEmpty()) { pi++; continue }

                val lastChar = cur.lastOrNull()
                fun lastWord(s: String): String {
                    val parts = s.trim().split(Regex("\\s+"))
                    return parts.lastOrNull() ?: ""
                }
                val lastW = lastWord(cur).lowercase()
                val wordCount = cur.split(Regex("\\s+")).size

                val shouldMergeAggressive = (lastChar != null && !sentenceEnders.contains(lastChar)) &&
                        (wordCount <= 10 || lastW in continuationWords || lastW.length <= 4)

                if (shouldMergeAggressive) {
                    cur = (cur + " " + nxt).replace(Regex(" +"), " ")
                    pi++
                    continue
                }
                break
            }

            compacted.add(cur)
            pi++
        }

        val processedParagraphs = compacted.map { paragraph ->
            // Trim edges of the paragraph
            val p = paragraph.trim()
            if (p.isEmpty()) return@map ""

            // Replace multiple spaces with single space inside paragraph
            var builder = StringBuilder(p.replace(Regex(" +"), " "))

            // Walk through and replace single newlines according to rules:
            // - If the character immediately before the newline is a sentence end (.?!…:;"'”»)) keep the newline
            // - If the char before newline is a hyphen '-' (word split) remove the hyphen and the newline (no space)
            // - Otherwise replace the newline with a single space
            var i = 0
            while (i < builder.length) {
                val c = builder[i]
                if (c == '\n') {
                    // find previous non-space char
                    var prevIndex = i - 1
                    while (prevIndex >= 0 && builder[prevIndex].isWhitespace()) prevIndex--
                    val prevChar = if (prevIndex >= 0) builder[prevIndex] else null

                    // find next non-space char
                    var nextIndex = i + 1
                    while (nextIndex < builder.length && builder[nextIndex].isWhitespace()) nextIndex++
                    val nextChar = if (nextIndex < builder.length) builder[nextIndex] else null

                    val sentenceEnders = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')

                    // Find the next line snippet (look ahead to the next newline or end)
                    val nextLineEnd = builder.indexOf('\n', nextIndex).let { if (it == -1) builder.length else it }
                    val nextLineSnippet = if (nextIndex < builder.length) builder.substring(nextIndex, minOf(nextLineEnd, nextIndex + 60)).trimStart() else ""

                    // Heuristics to preserve newline:
                    // - Next line starts with a list marker (e.g., '1.', 'i.', '-', '*', '•')
                    // - Next line looks like a heading (short and starts with uppercase)
                    // - Next line starts with a quote/em-dash which often marks dialogue
                    val listMarkerRegex = Regex("^(\\d+\\.|[ivxIVX]+\\.|[-*•])\\s")
                    val startsWithQuoteOrDash = nextLineSnippet.startsWith("\"") || nextLineSnippet.startsWith("“") ||
                            nextLineSnippet.startsWith("—") || nextLineSnippet.startsWith("-") || nextLineSnippet.startsWith("'")
                        // Consider a heading only if the next line is very short (<=4 words)
                        // and either is in ALL CAPS/punctuation-heavy or ends with a colon.
                        val nextLineWords = nextLineSnippet.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        val looksLikeHeading = nextLineWords in 1..4 && nextLineSnippet.firstOrNull()?.isUpperCase() == true &&
                            (nextLineSnippet.uppercase() == nextLineSnippet || nextLineSnippet.trimEnd().endsWith(":"))
                    val preserveBecauseNextLine = listMarkerRegex.containsMatchIn(nextLineSnippet) || startsWithQuoteOrDash || looksLikeHeading

                    when {
                        prevChar == null -> {
                            // leading newline, just remove
                            builder.deleteCharAt(i)
                            continue
                        }
                        prevChar == '-' -> {
                            // word-split hyphen: remove the hyphen (prev) and the newline
                            builder.deleteCharAt(i) // remove newline
                            builder.deleteCharAt(prevIndex) // remove hyphen
                            i = maxOf(0, prevIndex)
                            continue
                        }
                        sentenceEnders.contains(prevChar) || preserveBecauseNextLine -> {
                            // keep paragraph style newline (convert to single newline)
                            // replace any whitespace around with a single newline
                            // remove any spaces before current pos
                            var j = i - 1
                            while (j >= 0 && builder[j].isWhitespace()) { builder.deleteCharAt(j); j-- ; i-- }
                            // remove any spaces after newline
                            var k = i + 1
                            while (k < builder.length && builder[k].isWhitespace()) { builder.deleteCharAt(k) }
                            // ensure a single newline remains
                            if (i >= builder.length || builder[i] != '\n') {
                                // already removed, skip
                                continue
                            }
                            i++
                            continue
                        }
                        else -> {
                            // default: join lines
                            // remove the newline and ensure a single space at that position
                            builder.deleteCharAt(i)
                            // insert space if previous char is not space and next char is not punctuation
                            // prevChar is non-null here (handled above), assert non-null to satisfy Kotlin
                            val pChar = prevChar!!
                            val needSpace = !pChar.isWhitespace() && pChar != '-' &&
                                            (nextChar != null && !nextChar.isWhitespace() && nextChar != ',' && nextChar != '.')
                            if (needSpace) {
                                builder.insert(i, ' ')
                                i++
                            }
                            continue
                        }
                    }
                }
                i++
            }

            // After the iterative pass, also collapse any remaining newlines that
            // clearly start with a lowercase letter or digit (continuation lines).
            // This is conservative: we only join newlines followed by lowercase/digit
            // since headings and dialogue often start with uppercase or punctuation.
            builder.toString().replace(Regex("\\n(?=[a-z0-9])"), " ")
        }

        // Re-join paragraphs with double newline and normalize multiple blank lines
        val joined = processedParagraphs.joinToString("\n\n").replace(Regex("\n{3,}"), "\n\n")

        // Next, consider collapsing accidental paragraph breaks (double
        // newlines) where the split looks like a line-wrap artifact rather
        // than a true paragraph boundary. We merge when the left part does
        // not end with a sentence terminator and is short or ends with a
        // continuation word.
        val parts = joined.split("\n\n").map { it.trim() }.toMutableList()
        var pi2 = 0
        while (pi2 < parts.size - 1) {
            val left = parts[pi2]
            val right = parts[pi2 + 1]
            if (left.isEmpty() || right.isEmpty()) { pi2++; continue }

            val lastChar = left.lastOrNull()
            fun lastWord(s: String): String {
                val parts = s.trim().split(Regex("\\s+"))
                return parts.lastOrNull() ?: ""
            }
            val lastW = lastWord(left).lowercase()
            val leftWordCount = left.split(Regex("\\s+")).size

            val sentenceEnders2 = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
            val continuationWords2 = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an")

            val shouldCollapseParagraph = (lastChar != null && !sentenceEnders2.contains(lastChar)) &&
                    (leftWordCount <= 10 || lastW in continuationWords2 || lastW.length <= 4)

            if (shouldCollapseParagraph) {
                parts[pi2] = (left + " " + right).replace(Regex(" +"), " ")
                parts.removeAt(pi2 + 1)
                // stay on same index to see if we can collapse further
            } else {
                pi2++
            }
        }

        val collapsed = parts.joinToString("\n\n")

        // Additional aggressive cleanup pass: some sources split mid-sentence
        // across paragraph tags or inserted extra blank lines. Conservatively
        // merge paragraph pairs where the right paragraph begins with a
        // lowercase letter or digit (strong signal of a continuation) and
        // the left paragraph does not end with a sentence terminator.
        val postParts = collapsed.split("\n\n").map { it.trim() }.toMutableList()
        var idx = 0
        while (idx < postParts.size - 1) {
            val left = postParts[idx]
            val right = postParts[idx + 1]
            if (left.isEmpty() || right.isEmpty()) { idx++; continue }

            val leftLast = left.lastOrNull()
            val rightFirst = right.firstOrNull()
            val sentenceEnders3 = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')

            // If right starts with lowercase or digit, and left does not end
            // with a sentence-ender, merge them. This aggressively collapses
            // accidental paragraph boundaries while preserving true breaks.
            val shouldMergeBecauseRightIsContinuation = (rightFirst != null && (rightFirst.isLowerCase() || rightFirst.isDigit())) &&
                    (leftLast == null || !sentenceEnders3.contains(leftLast))

            if (shouldMergeBecauseRightIsContinuation) {
                postParts[idx] = (left + " " + right).replace(Regex(" +"), " ")
                postParts.removeAt(idx + 1)
                // do not increment idx so we can keep collapsing if needed
            } else {
                idx++
            }
        }

        var finallyCollapsed = postParts.joinToString("\n\n")

        // Collapse accidental leftover single newlines into spaces while preserving paragraphs
        val collapsedSingleNewlines = finallyCollapsed.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")

        // Trim extra whitespace and return
        return collapsedSingleNewlines.replace(Regex("[ ]{2,}"), " ").trim()
    }
}
