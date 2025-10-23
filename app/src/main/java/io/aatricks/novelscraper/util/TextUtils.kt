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
}
