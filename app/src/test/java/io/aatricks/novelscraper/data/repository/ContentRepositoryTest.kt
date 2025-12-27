package io.aatricks.novelscraper.data.repository

import org.junit.Test
import org.junit.Assert.*
import org.jsoup.Jsoup
import io.aatricks.novelscraper.util.TextUtils
import java.io.File

class ContentRepositoryTest {

    @Test
    fun testParseHtmlDocument() {
        val html = """
            <html>
            <head><title>Test Chapter</title></head>
            <body>
                <div class="content">
                    <p>This is paragraph one.</p>
                    <p>This is paragraph two, which is split</p>
                    <p>across two tags.</p>
                    <p>This is paragraph three.</p>
                    <p class="chapter-title">Chapter 525: The Battle</p>
                    <p>Some text<br>with a line break.</p>
                    <p>Dialogue line one.</p>
                    <p>Dialogue line two.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val document = Jsoup.parse(html)
        val paragraphs = mutableListOf<String>()
        val contentSelectors = listOf(".content p")

        // Mimic ContentRepository logic
        for (selector in contentSelectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val htmlContent = element.html().replace(Regex("(?i)<br\\s*/?>"), "[[LINE_BREAK]][[LINE_BREAK]]")
                    val text = Jsoup.parseBodyFragment(htmlContent).text().replace("[[LINE_BREAK]]", "\n")
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                    }
                }
                if (paragraphs.isNotEmpty()) break
            }
        }

        // Apply merge logic
        if (paragraphs.size > 1) {
            val merged = mutableListOf<String>()
            var idx = 0
            val sentenceEnders = setOf('.', '!', '?', '…', '"', '\'', '‘', '’', '“', '”', '»', ':', ';')
            val continuationWords = setOf("of", "to", "for", "and", "but", "or", "the", "a", "an", "my", "his", "her", "their", "its", "in", "on", "at", "from", "with")

            fun lastWord(s: String): String {
                val parts = s.trim().split(Regex("\\s+"))
                return parts.lastOrNull() ?: ""
            }

            while (idx < paragraphs.size) {
                var cur = paragraphs[idx].trim()
                if (cur.isEmpty()) { idx++; continue }

                if (idx + 1 < paragraphs.size) {
                    val next = paragraphs[idx + 1].trim()
                    if (next.isNotEmpty()) {
                        val lastChar = cur.lastOrNull()
                        val lastW = lastWord(cur).lowercase()
                        val wordCount = cur.split(Regex("\\s+")).size

                        val shouldMerge = (lastChar != null && !sentenceEnders.contains(lastChar)) &&
                                (wordCount <= 8 || lastW in continuationWords || lastW.length <= 4)

                        if (shouldMerge) {
                            cur = (cur + " " + next).replace(Regex(" +"), " ")
                            idx += 2
                            while (idx < paragraphs.size) {
                                val peek = paragraphs[idx].trim()
                                if (peek.isEmpty()) { idx++; continue }
                                val peekFirst = peek.firstOrNull()
                                if (peekFirst != null && peekFirst.isUpperCase() && cur.trim().lastOrNull()?.let { sentenceEnders.contains(it) } == true) break
                                cur = (cur + " " + peek).replace(Regex(" +"), " ")
                                idx++
                            }
                            merged.add(cur)
                            continue
                        }
                    }
                }
                merged.add(cur)
                idx++
            }
            paragraphs.clear()
            paragraphs.addAll(merged)
        }

        // Apply TextUtils.formatChapterText logic
        val joined = paragraphs.distinct().joinToString("\n\n")
        val formatted = TextUtils.formatChapterText(joined)
        var formattedParagraphs = formatted.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }

        println("Paragraphs: $formattedParagraphs")

        // Assertions
        // "This is paragraph two, which is split across two tags." should be merged
        assertTrue(formattedParagraphs.any { it.contains("This is paragraph two, which is split across two tags.") })
        // "Some text" and "with a line break." should be separate paragraphs due to <br> -> \n\n replacement
        assertTrue(formattedParagraphs.any { it == "Some text" })
        assertTrue(formattedParagraphs.any { it == "with a line break." })
    }
}
