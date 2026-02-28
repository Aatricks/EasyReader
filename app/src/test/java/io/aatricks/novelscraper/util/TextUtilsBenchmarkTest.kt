package io.aatricks.novelscraper.util

import org.junit.Test
import kotlin.system.measureTimeMillis

class TextUtilsBenchmarkTest {

    @Test
    fun benchmarkExtractBaseTitle() {
        val web = io.aatricks.novelscraper.data.model.ContentType.WEB
        val text = "Read Solo Max-Level Newbie Chapter 233 Free Online | MangaBat"

        // Warmup
        for (i in 1..100) {
            TextUtils.extractBaseTitle(text, web)
        }

        // Measure
        val time = measureTimeMillis {
            for (i in 1..50000) {
                TextUtils.extractBaseTitle(text, web)
            }
        }

        println("Benchmark ExtractBaseTitle: $time ms for 50000 iterations")
    }

    @Test
    fun benchmarkExtractChapterLabel() {
        val text = "Read Chapter 233 Free Online | MangaBat"

        // Warmup
        for (i in 1..100) {
            TextUtils.extractChapterLabel(text)
        }

        // Measure
        val time = measureTimeMillis {
            for (i in 1..50000) {
                TextUtils.extractChapterLabel(text)
            }
        }

        println("Benchmark ExtractChapterLabel: $time ms for 50000 iterations")
    }
}
