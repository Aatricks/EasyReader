package io.aatricks.easyreader.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiInitializationBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun initializeCachedModel() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val checkOutput = device.executeShellCommand(aiBroadcastCommand(ACTION_CHECK))
        assumeTrue(
            "AI benchmark requires the Qwen model to be cached before the run: $checkOutput",
            checkOutput.contains("result=-1") && checkOutput.contains("READY:model-cached")
        )
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = memoryMetrics(),
            compilationMode = CompilationMode.DEFAULT,
            iterations = BENCHMARK_ITERATIONS,
            setupBlock = { killProcess() }
        ) {
            val output = device.executeShellCommand(aiBroadcastCommand(ACTION_INITIALIZE))
            check(output.contains("result=-1") && output.contains("READY:ai-initialized")) {
                "AI initialization failed: $output"
            }
        }
    }

    private fun aiBroadcastCommand(action: String): String =
        "am broadcast --receiver-foreground -f 0x20 -a $action " +
            "-n $TARGET_PACKAGE/io.aatricks.easyreader.benchmark.BenchmarkAiInitializationReceiver"

    private companion object {
        private const val ACTION_CHECK = "io.aatricks.easyreader.benchmark.CHECK_AI_MODEL"
        private const val ACTION_INITIALIZE = "io.aatricks.easyreader.benchmark.INITIALIZE_AI"
    }
}
