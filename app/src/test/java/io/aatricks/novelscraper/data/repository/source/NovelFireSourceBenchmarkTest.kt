package io.aatricks.novelscraper.data.repository.source

import org.junit.Test
import kotlin.system.measureTimeMillis

class NovelFireSourceBenchmarkTest {

    private fun cleanNovelTitleOriginal(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(Regex("^\\[\\d+\\]\\s*"), "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(Regex("^R\\s*\\d+(\\.\\d+)?\\s*"), "")
        // Remove Rank 123 at start
        clean = clean.replace(Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }

    companion object {
        private val BRACKET_NUMBER_REGEX = Regex("^\\[\\d+\\]\\s*")
        private val R_NUMBER_REGEX = Regex("^R\\s*\\d+(\\.\\d+)?\\s*")
        private val RANK_PREFIX_REGEX = Regex("^Rank\\s*\\d+\\s*", RegexOption.IGNORE_CASE)
    }

    private fun cleanNovelTitleOptimized(title: String): String {
        var clean = title
        // Remove [123] at start
        clean = clean.replace(BRACKET_NUMBER_REGEX, "")
        // Remove R 14.8 or R 123 at start
        clean = clean.replace(R_NUMBER_REGEX, "")
        // Remove Rank 123 at start
        clean = clean.replace(RANK_PREFIX_REGEX, "")
        return clean.trim()
    }

    @Test
    fun benchmarkCleanNovelTitle() {
        val titles = listOf(
            "[123] Some Novel Name",
            "R 14.8 Another Novel Name",
            "Rank 42 A Third Novel Name",
            "Just a regular name",
            "Rank 123 [123] Double trouble name",
            "Nothing to replace here 123"
        )

        // Warmup
        for (i in 0 until 1000) {
            for (title in titles) {
                cleanNovelTitleOriginal(title)
                cleanNovelTitleOptimized(title)
            }
        }

        val iterations = 100_000
        val timeOriginal = measureTimeMillis {
            for (i in 0 until iterations) {
                for (title in titles) {
                    cleanNovelTitleOriginal(title)
                }
            }
        }

        val timeOptimized = measureTimeMillis {
            for (i in 0 until iterations) {
                for (title in titles) {
                    cleanNovelTitleOptimized(title)
                }
            }
        }

        println("Baseline - Time to run $iterations iterations: $timeOriginal ms")
        println("Optimized - Time to run $iterations iterations: $timeOptimized ms")
        println("Improvement: ${timeOriginal - timeOptimized} ms")
    }
}
