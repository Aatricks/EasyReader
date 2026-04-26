package io.aatricks.easyreader.util

internal object TextFormattingPipeline {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MULTIPLE_SPACES_REGEX = Regex(" +")
    private val LINE_BREAK_REGEX = Regex("\\r\\n|\\r")
    private val THREE_PLUS_NEWLINES_REGEX = Regex("\\n{3,}")
    private val PARAGRAPH_SPLIT_REGEX = Regex("(?s)(.*?)(\\n+|$)")
    private val LIST_MARKER_REGEX = Regex("^(?:\\d+|[ivxIVX]+\\.|[-*•])\\s")
    private val SINGLE_NEWLINE_REGEX = Regex("(?<!\\n)\\n(?!\\n)")
    private val TWO_PLUS_SPACES_REGEX = Regex("[ ]{2,}")

    fun formatText(text: String): String {
        if (text.isEmpty()) return text

        var current = text
        current = replaceSpacesPlusNewline(current, ' ')
        current = replaceWindowsLineEndings(current)
        current = replaceSpacesPlusNewline(current, '\n')
        current = replaceFourPlusNewlines(current)
        return current.trim()
    }

    fun formatChapterText(text: String): String {
        if (text.isEmpty()) return text
        val normalized = text.trim().replace(LINE_BREAK_REGEX, "\n")

        val rawParagraphs = initialParagraphSplit(normalized)
        val initialMerged = mergeAccidentalSplits(rawParagraphs, normalized)
        val compacted = compactParagraphs(initialMerged, normalized)
        val processed = processIndividualParagraphs(compacted)

        return finalCollapse(processed, normalized)
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
                    sb.append("\n\n\n")
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
                            i++
                            continue
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
        return TextHeuristics.shouldMergeSentenceFragments(
            current = cur,
            next = next,
            maxWordCount = 8,
            isHeading = ::isHeading,
            preventDualColonMerge = true
        )
    }

    private fun isHeading(text: String): Boolean {
        val firstChar = text.firstOrNull() ?: return false
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        val isAllUpper = text.uppercase() == text
        return firstChar.isUpperCase() && words.size in 1..4 && (isAllUpper || text.trimEnd().endsWith(":"))
    }

    private fun shouldStopGreedyMerge(cur: String, peek: String): Boolean {
        return TextHeuristics.shouldStopGreedyMerge(cur, peek)
    }

    private fun compactParagraphs(paragraphs: List<String>, original: String): List<String> {
        val compacted = mutableListOf<String>()
        var pi = 0
        while (pi < paragraphs.size) {
            var cur = paragraphs[pi++].trim()

            while (pi < paragraphs.size) {
                val nxt = paragraphs[pi].trim()
                if (nxt.isEmpty()) {
                    pi++
                    continue
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
        return TextHeuristics.shouldMergeSentenceFragments(
            current = cur,
            next = next,
            maxWordCount = 10,
            isHeading = ::isHeading,
            preventDualColonMerge = true
        )
    }

    private fun processIndividualParagraphs(paragraphs: List<String>): List<String> {
        return paragraphs.map {
            if (it.trim().isEmpty()) return@map ""

            val lines = it.split('\n').map { line -> line.trim() }.filter { line -> line.isNotEmpty() }
            if (lines.size <= 1) return@map it.trim()

            val sb = StringBuilder(lines[0])
            for (i in 1 until lines.size) {
                val prevLine = lines[i - 1]
                val curLine = lines[i]
                val lastChar = prevLine.lastOrNull() ?: ' '

                if (TextHeuristics.SENTENCE_ENDERS.contains(lastChar) || isHeading(curLine) || LIST_MARKER_REGEX.containsMatchIn(curLine)) {
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
                i++
                continue
            }

            val leftLast = left.lastOrNull() ?: ' '
            val rightFirst = right.firstOrNull() ?: ' '

            val shouldCollapse = !TextHeuristics.SENTENCE_ENDERS.contains(leftLast) &&
                (rightFirst.isLowerCase() || rightFirst.isDigit())

            if (shouldCollapse) {
                parts[i] = (left + " " + right).replace(MULTIPLE_SPACES_REGEX, " ")
                parts.removeAt(i + 1)
            } else i++
        }
    }
}
