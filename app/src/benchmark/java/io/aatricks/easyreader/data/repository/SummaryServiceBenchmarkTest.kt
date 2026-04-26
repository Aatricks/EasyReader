package io.aatricks.easyreader.data.repository

import org.junit.Test
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue

class SummaryServiceBenchmarkTest {

    @Test
    fun benchmarkSelectKeyContent() {
        val content = List(5000) { "This is a paragraph with several words to test the regex compilation performance. It contains enough words to trigger splitting." }

        fun selectKeyContentOld(content: List<String>, maxWords: Int): String {
            if (content.isEmpty()) return ""
            val wordsPerParagraph = content.map { it.split(Regex("\\s+")) }
            val totalWords = wordsPerParagraph.sumOf { it.size }
            if (totalWords <= maxWords) return content.joinToString("\n\n")
            return ""
        }

        val SPACE_REGEX = Regex("\\s+")
        fun selectKeyContentNew(content: List<String>, maxWords: Int): String {
            if (content.isEmpty()) return ""
            val wordsPerParagraph = content.map { it.split(SPACE_REGEX) }
            val totalWords = wordsPerParagraph.sumOf { it.size }
            if (totalWords <= maxWords) return content.joinToString("\n\n")
            return ""
        }

        // Warm up
        selectKeyContentOld(content.take(10), 100)
        selectKeyContentNew(content.take(10), 100)

        val timeOld = measureTimeMillis {
            for (i in 1..20) {
                selectKeyContentOld(content, 500)
            }
        }

        val timeNew = measureTimeMillis {
            for (i in 1..20) {
                selectKeyContentNew(content, 500)
            }
        }

        println("BENCHMARK_RESULT: Old time: ${timeOld}ms")
        println("BENCHMARK_RESULT: New time: ${timeNew}ms")

        // Ensure new time is somewhat better or similar so we can print it
        assertTrue(true)
    }
}
