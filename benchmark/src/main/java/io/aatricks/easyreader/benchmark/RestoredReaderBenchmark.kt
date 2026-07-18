package io.aatricks.easyreader.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoredReaderBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun restoredThousandParagraphReaderFirstContent() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = launchMetrics(),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = { prepareFixture(Fixture.TEXT_1000_SCROLL) }
    ) {
        pressHome()
        startActivityAndWait()
        requireObject(By.textContains("Benchmark paragraph 1"), "restored first paragraph")
    }
}
