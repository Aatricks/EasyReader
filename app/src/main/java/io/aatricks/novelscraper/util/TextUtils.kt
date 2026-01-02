package io.aatricks.novelscraper.util

import java.net.URI
import java.util.regex.Pattern

/**
 * Utility functions for text processing and manipulation.
 * Handles text formatting, page number removal, and URL manipulation for chapter navigation.
 */
object TextUtils {

    /**
     * Remove page numbers from text content.
     */
    fun removePageNumbers(text: String, isPdfContent: Boolean = false): String {
        if (text.isEmpty()) return text
        if (!isPdfContent) return text

        // Simplified logic: remove lines that are just numbers
        return text.lines().filterNot { it.trim().matches(Regex("\\d+")) }.joinToString("\n")
    }

    /**
     * Remove "Page |" or "Page " prefix from text
     */
    fun removePageWord(text: String): String {
        if (text.isEmpty()) return text
        return text.replace(Regex("Page \\|\\s*|Page\\s+"), "")
    }

    /**
     * Increment the chapter number in a URL
     */
    fun incrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val pattern = Pattern.compile("(\\d+)(?!.*\\d)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) {
            val num = matcher.group(1).toInt()
            matcher.replaceFirst((num + 1).toString())
        } else url
    }

    /**
     * Decrement the chapter number in a URL
     */
    fun decrementChapterInUrl(url: String): String {
        if (url.isEmpty()) return url
        val pattern = Pattern.compile("(\\d+)(?!.*\\d)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) {
            val num = matcher.group(1).toInt()
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

        // Split into paragraphs on 2+ newlines, keeping the separator length for each paragraph
        // so we can avoid merging paragraphs that were intentionally separated
        val rawParagraphsWithSep = Regex("(?s)(.*?)(\\n{2,}|$)").findAll(normalized)
            .map { it.groupValues[1] to it.groupValues[2].length }
            .toList()
        var rawParagraphs = rawParagraphsWithSep.map { it.first }

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
                // If the original input had a deliberate paragraph break of 2+ newlines
                // between these two paragraphs, do not attempt to merge them.
                if (rawParagraphsWithSep[i].second >= 2) {
                    paragraphs.add(cur)
                    i++
                    continue
                }
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
                            (wordCount <= 8 || lastW in continuationWords || lastW.length <= 4) &&
                            !(cur.contains(':') && next.contains(':'))

                        // If the next paragraph looks like a heading (short, starts with uppercase),
                        // avoid merging as it likely indicates an intentional paragraph break.
                        val nextFirstChar = next.firstOrNull()
                        val nextWordCount = next.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        val looksLikeHeading = nextFirstChar != null && nextFirstChar.isUpperCase() && nextWordCount in 1..4 &&
                            (next.uppercase() == next || next.trimEnd().endsWith(":"))

                        if (shouldMerge && !looksLikeHeading) {
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

                // If the original normalized text has a hard paragraph separation
                // between cur and nxt, do not attempt to aggressively merge them.
                if (normalized.contains("${cur}\n\n${nxt}")) break

                val lastChar = cur.lastOrNull()
                fun lastWord(s: String): String {
                    val parts = s.trim().split(Regex("\\s+"))
                    return parts.lastOrNull() ?: ""
                }
                val lastW = lastWord(cur).lowercase()
                val wordCount = cur.split(Regex("\\s+")).size

                val nextFirst = nxt.firstOrNull()
                val nextWordCountAgg = nxt.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                val looksLikeHeadingAgg = nextFirst != null && nextFirst.isUpperCase() && nextWordCountAgg in 1..4 &&
                    (nxt.uppercase() == nxt || nxt.trimEnd().endsWith(":"))

                val shouldMergeAggressive = (lastChar != null && !sentenceEnders.contains(lastChar)) &&
                    (wordCount <= 10 || lastW in continuationWords || lastW.length <= 4) && !looksLikeHeadingAgg &&
                    !(cur.contains(':') && nxt.contains(':'))

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

            // Respect explicit paragraph separators in the original text
            if (normalized.contains("${left}\n\n${right}")) { pi2++; continue }

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

            // Respect explicit paragraph separators in the original text
            if (normalized.contains("${left}\n\n${right}")) { idx++; continue }

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

    // Debug helper used in tests to inspect how paragraphs are split and
    // what separators (number of newlines) were found between them.
    fun debugGetParagraphsWithSeparators(text: String): List<Pair<String, Int>> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.trim().replace(Regex("\\r\\n|\\r"), "\n")
        return Regex("(?s)(.*?)(\\n{2,}|$)").findAll(normalized)
            .map { it.groupValues[1].trim() to it.groupValues[2].length }
            .toList()
    }
}
