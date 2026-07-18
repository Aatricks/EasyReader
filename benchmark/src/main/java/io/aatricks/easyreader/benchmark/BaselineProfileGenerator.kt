package io.aatricks.easyreader.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun generateReaderAndLibraryProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false
    ) {
        prepareFixture(Fixture.LIBRARY_500)
        startActivityAndWait()
        openLibraryScreen()
        expandLibrarySeries()
        scrollVertically(repetitions = 1)
        browseAllChapters()
        scrollVertically()

        prepareFixture(Fixture.TEXT_1000_SCROLL)
        startActivityAndWait()
        requireObject(By.textContains("Benchmark paragraph 1"), "restored first paragraph")
        scrollVertically()

        prepareFixture(Fixture.TEXT_1000_PAGED)
        startActivityAndWait()
        requireObject(By.textContains("Benchmark paragraph 1"), "first paged paragraph")
        pageForward()

        prepareFixture(Fixture.MANHWA_TALL)
        startActivityAndWait()
        requirePackage()
        scrollVertically(repetitions = 12)

        prepareFixture(Fixture.PDF)
        startActivityAndWait()
        requireObject(By.textContains("Benchmark PDF"), "PDF reader content")
    }
}
