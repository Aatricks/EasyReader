@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package io.aatricks.easyreader.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "io.aatricks.novelscraper"
internal const val BENCHMARK_ITERATIONS = 3

internal enum class Fixture(val wireName: String) {
    LIBRARY_500("library500"),
    TEXT_500_PAGED("text500Paged"),
    TEXT_1000_PAGED("text1000Paged"),
    TEXT_1000_SCROLL("text1000Scroll"),
    MANHWA_TALL("manhwaTall"),
    PDF("pdf")
}

internal fun launchMetrics(): List<Metric> = listOf(
    StartupTimingMetric(),
    FrameTimingMetric(),
    peakMemoryMetric()
)

internal fun interactionMetrics(): List<Metric> = listOf(
    FrameTimingMetric(),
    peakMemoryMetric()
)

internal fun memoryMetrics(): List<Metric> = listOf(peakMemoryMetric())

private fun peakMemoryMetric(): MemoryUsageMetric = MemoryUsageMetric(
    mode = MemoryUsageMetric.Mode.Max,
    subMetrics = listOf(
        MemoryUsageMetric.SubMetric.HeapSize,
        MemoryUsageMetric.SubMetric.RssAnon,
        MemoryUsageMetric.SubMetric.RssFile,
        MemoryUsageMetric.SubMetric.RssShmem,
        MemoryUsageMetric.SubMetric.Gpu
    )
)

internal fun MacrobenchmarkScope.prepareFixture(fixture: Fixture) {
    killProcess()
    val output = device.executeShellCommand(
        "am broadcast --receiver-foreground -f 0x20 " +
            "-a io.aatricks.easyreader.benchmark.SEED " +
            "-n $TARGET_PACKAGE/io.aatricks.easyreader.benchmark.BenchmarkFixtureReceiver " +
            "--es fixture ${fixture.wireName}"
    )
    check(output.contains("result=-1") && output.contains("READY:${fixture.wireName}")) {
        "Fixture ${fixture.wireName} failed: $output"
    }
    killProcess()
}

internal fun MacrobenchmarkScope.requireObject(
    selector: BySelector,
    description: String
) = checkNotNull(device.wait(Until.findObject(selector), UI_TIMEOUT_MS)) {
    "Timed out waiting for $description"
}

internal fun MacrobenchmarkScope.requirePackage() {
    check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS)) {
        "Timed out waiting for $TARGET_PACKAGE"
    }
}

internal fun MacrobenchmarkScope.openLibraryScreen() {
    val width = device.displayWidth
    val height = device.displayHeight
    // Swipe injection can transiently fail on physical devices; verify the drawer
    // actually opened rather than trusting a single injection result.
    var drawerAction: androidx.test.uiautomator.UiObject2? = null
    for (attempt in 1..DRAWER_OPEN_ATTEMPTS) {
        device.swipe(width / DRAWER_START_DIVISOR, height / 2, width * 3 / 4, height / 2, DRAWER_SWIPE_STEPS)
        drawerAction = device.wait(Until.findObject(By.text("Library")), DRAWER_OPEN_WAIT_MS)
        if (drawerAction != null) break
    }
    checkNotNull(drawerAction) { "Library drawer did not open after $DRAWER_OPEN_ATTEMPTS swipes" }.click()
    requireObject(By.desc("Browse chapters"), "Benchmark Series library row")
}

internal fun MacrobenchmarkScope.scrollVertically(repetitions: Int = DEFAULT_SCROLLS) {
    val width = device.displayWidth
    val height = device.displayHeight
    repeat(repetitions) {
        swipeWithRetry(width / 2, height * 3 / 4, width / 2, height * 3 / 10)
    }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.pageForward(repetitions: Int = DEFAULT_PAGE_SWIPES) {
    val width = device.displayWidth
    val height = device.displayHeight
    repeat(repetitions) {
        swipeWithRetry(width * 3 / 4, height / 2, width / 4, height / 2)
    }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.awaitLibraryQuiet() {
    // Opening the library reconciles seeded offline caches; the "Refreshing offline
    // cache" snackbar covers bottom-anchored controls and the spinner shifts rows.
    // Wait for it to clear before driving the list (no-op when it never appears).
    device.wait(Until.gone(By.textContains("Refreshing offline cache")), REFRESH_QUIET_TIMEOUT_MS)
    device.waitForIdle()
}

// The library reconciles seeded offline caches with a spinner that overlaps the series
// card and a snackbar over the bottom controls, so any single click can be swallowed.
// Both helpers verify the click's effect and retry instead of trusting one injection.
internal fun MacrobenchmarkScope.expandLibrarySeries() {
    awaitLibraryQuiet()
    var expanded = false
    for (attempt in 1..CLICK_EFFECT_ATTEMPTS) {
        device.findObject(By.desc("Browse chapters"))?.click()
        expanded = device.wait(Until.hasObject(By.desc("Hide chapters")), CLICK_EFFECT_WAIT_MS)
        if (expanded) break
        awaitLibraryQuiet()
    }
    check(expanded) { "Series did not expand after $CLICK_EFFECT_ATTEMPTS attempts" }
    requireObject(By.text("Chapter 1"), "first preview chapter")
}

internal fun MacrobenchmarkScope.browseAllChapters() {
    awaitLibraryQuiet()
    for (attempt in 1..CLICK_EFFECT_ATTEMPTS) {
        if (device.hasObject(By.text("Chapter 4"))) return
        // The refresh's data reload can re-collapse the series mid-journey.
        if (device.hasObject(By.desc("Browse chapters"))) {
            expandLibrarySeries()
        }
        if (device.hasObject(By.textContains("Browse all chapters"))) {
            clickWhenStable(By.textContains("Browse all chapters"), "Browse all chapters control")
        }
        if (device.wait(Until.hasObject(By.text("Chapter 4")), CLICK_EFFECT_WAIT_MS)) return
        awaitLibraryQuiet()
    }
    requireObjectScrolling(By.text("Chapter 4"), "first chapter beyond the preview")
}

internal fun MacrobenchmarkScope.clickWhenStable(selector: BySelector, description: String) {
    // A fling's overscroll bounce can leave freshly-read bounds stale by click time,
    // landing the tap on a neighboring row. Click only once bounds repeat.
    var bounds = requireObjectScrolling(selector, description).visibleBounds
    repeat(STABLE_READ_ATTEMPTS) {
        Thread.sleep(STABLE_READ_INTERVAL_MS)
        val fresh = requireObject(selector, description)
        val freshBounds = fresh.visibleBounds
        if (freshBounds == bounds) {
            fresh.click()
            return
        }
        bounds = freshBounds
    }
    requireObject(selector, description).click()
}

internal fun MacrobenchmarkScope.requireObjectScrolling(
    selector: BySelector,
    description: String
): androidx.test.uiautomator.UiObject2 {
    val width = device.displayWidth
    val height = device.displayHeight
    // Down-only: an upward drag at list top is the pull-to-refresh gesture, which
    // kicks off a long offline-cache refresh that destroys the journey's UI state.
    repeat(SCROLL_SEARCH_ATTEMPTS) {
        device.findObject(selector)?.let { return it }
        swipeWithRetry(width / 2, height * 3 / 4, width / 2, height / 2)
        device.waitForIdle()
    }
    return requireObject(selector, description)
}

private fun MacrobenchmarkScope.swipeWithRetry(startX: Int, startY: Int, endX: Int, endY: Int) {
    if (!device.swipe(startX, startY, endX, endY, SWIPE_STEPS)) {
        check(device.swipe(startX, startY, endX, endY, SWIPE_STEPS)) {
            "Swipe injection failed twice at ($startX,$startY)->($endX,$endY)"
        }
    }
}

private const val UI_TIMEOUT_MS = 10_000L
private const val DEFAULT_SCROLLS = 8
private const val DEFAULT_PAGE_SWIPES = 4
private const val SWIPE_STEPS = 100
private const val DRAWER_START_DIVISOR = 4
private const val DRAWER_SWIPE_STEPS = 200
private const val DRAWER_OPEN_ATTEMPTS = 3
private const val DRAWER_OPEN_WAIT_MS = 2_000L
private const val SCROLL_SEARCH_ATTEMPTS = 4
private const val STABLE_READ_ATTEMPTS = 5
private const val STABLE_READ_INTERVAL_MS = 250L
private const val REFRESH_QUIET_TIMEOUT_MS = 20_000L
private const val CLICK_EFFECT_ATTEMPTS = 3
private const val CLICK_EFFECT_WAIT_MS = 5_000L
