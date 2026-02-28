package io.aatricks.novelscraper.util

import org.junit.Test
import kotlin.system.measureTimeMillis

class TextUtilsBenchmarkTest {

    @Test
    fun benchmarkExtractChapterLabel() {
        val iterations = 100_000
        val inputs = listOf(
            "Read Chapter 233 Free Online | MangaBat",
            "Chapter 175 - The End",
            "The Great Mage Returns After 4000 Years: 150",
            "Ch. 10",
            "Just some title",
            null,
            ""
        )

        // Warmup
        for (i in 0 until 1000) {
            inputs.forEach { TextUtils.extractChapterLabel(it) }
        }

        val timeTaken = measureTimeMillis {
            for (i in 0 until iterations) {
                inputs.forEach { TextUtils.extractChapterLabel(it) }
            }
        }

        println("Baseline: extractChapterLabel took ${timeTaken}ms for ${iterations * inputs.size} invocations.")
    }
}
