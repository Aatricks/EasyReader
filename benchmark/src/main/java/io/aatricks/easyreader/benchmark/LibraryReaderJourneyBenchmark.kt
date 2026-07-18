package io.aatricks.easyreader.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryReaderJourneyBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun expandFiveHundredChapterLibrary() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = interactionMetrics(),
        compilationMode = CompilationMode.DEFAULT,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            prepareFixture(Fixture.LIBRARY_500)
            startActivityAndWait()
            openLibraryScreen()
        }
    ) {
        expandLibrarySeries()
        scrollVertically(repetitions = 1)
        browseAllChapters()
        scrollVertically()
    }
}
