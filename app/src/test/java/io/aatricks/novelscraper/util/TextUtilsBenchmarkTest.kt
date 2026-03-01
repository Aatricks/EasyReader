package io.aatricks.novelscraper.util

import org.junit.Test
import kotlin.system.measureTimeMillis

class TextUtilsBenchmarkTest {

    @Test
    fun benchmarkFormatText() {
        val sb = StringBuilder()
        for (i in 0 until 1000) {
            sb.append("This is a test line.    \n")
            sb.append("Another line with Windows endings.  \r\n")
            sb.append("\n\n\n\n\n")
            sb.append("A normal paragraph with some text and more text. ")
        }
        val text = sb.toString()

        for (i in 0 until 10) {
            TextUtils.formatText(text)
        }

        var totalTime = 0L
        val iterations = 500
        for (i in 0 until iterations) {
            totalTime += measureTimeMillis {
                TextUtils.formatText(text)
            }
        }

        println("Optimized average time: ${totalTime / iterations.toDouble()} ms")
    }

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
