package io.aatricks.easyreader.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.repository.SummaryService
import io.aatricks.easyreader.data.repository.summary.DisabledSummaryEngine
import io.aatricks.easyreader.data.repository.summary.SummaryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SummaryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var summaryEngine: DisabledSummaryEngine
    private lateinit var summaryService: SummaryService
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: SummaryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        summaryEngine = DisabledSummaryEngine()
        summaryService = SummaryService(summaryEngine, testDispatcher)
        preferencesManager = PreferencesManager(ApplicationProvider.getApplicationContext())
        preferencesManager.aiSummaryEnabled = true
        viewModel = SummaryViewModel(summaryService, preferencesManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `when initialized with disabled engine, reporting ready should be false`() {
        assertFalse(viewModel.isServiceReady())
    }

    @Test
    fun `when generating summary with disabled engine, should set error state`() = runTest(testDispatcher) {
        val chapterUrl = "https://example.com/chapter1"
        val content = listOf("Paragraph 1", "Paragraph 2")
        
        viewModel.generateSummary(chapterUrl, "Chapter 1", content) {
            // Callback should not be called with a valid summary
        }
        
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertFalse(state.isGenerating)
        assertNotNull(state.error)
        assertEquals("AI Summarization is disabled in this build.", state.error)
        assertNull(state.currentSummary)
    }

    @Test
    fun `initializeSummaryService is a no-op when the build does not support AI`() = runTest(testDispatcher) {
        viewModel.initializeSummaryService()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.supportsAi)
        assertFalse(state.isEnabled)
        assertFalse(state.isInitializing)
        assertNull(state.error)
    }

    @Test
    fun `generateSummary replaces currentSummary with cumulative snapshots without appending`() = runTest(testDispatcher) {
        val progressSnapshots = mutableListOf<String?>()
        lateinit var customVm: SummaryViewModel
        val streamingEngine = object : SummaryEngine {
            override val supportsAi: Boolean = true
            override fun isAvailable(): Boolean = true
            override suspend fun initialize(): Result<Unit> = Result.success(Unit)
            override suspend fun generateSummary(
                prompt: String,
                onProgress: ((String) -> Unit)?
            ): Result<String> {
                onProgress?.invoke("A")
                kotlinx.coroutines.delay(10)
                progressSnapshots.add(customVm.uiState.value.currentSummary)
                onProgress?.invoke("AB")
                kotlinx.coroutines.delay(10)
                progressSnapshots.add(customVm.uiState.value.currentSummary)
                onProgress?.invoke("ABC")
                kotlinx.coroutines.delay(10)
                progressSnapshots.add(customVm.uiState.value.currentSummary)
                return Result.success("ABC")
            }
            override fun cancelGeneration() {}
            override fun release() {}
        }
        val customService = SummaryService(streamingEngine, testDispatcher)
        customVm = SummaryViewModel(customService, preferencesManager)
        var completedSummary: String? = null

        customVm.generateSummary("https://example.com/ch1", "Ch 1", listOf("text")) {
            completedSummary = it
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "AB", "ABC"), progressSnapshots)
        assertEquals("ABC", completedSummary)
        assertEquals("ABC", customVm.uiState.value.currentSummary)
        assertEquals("ABC", customVm.uiState.value.summariesCache["https://example.com/ch1"])
    }

    @Test
    fun `a failed generation stays attributed to its chapter and drops the partial text`() =
        runTest(testDispatcher) {
            val chapterUrl = "https://example.com/ch1"
            val failingEngine = object : SummaryEngine {
                override val supportsAi: Boolean = true
                override fun isAvailable(): Boolean = true
                override suspend fun initialize(): Result<Unit> = Result.success(Unit)
                override suspend fun generateSummary(
                    prompt: String,
                    onProgress: ((String) -> Unit)?
                ): Result<String> {
                    onProgress?.invoke("Half a sen")
                    return Result.failure(IllegalStateException("Ran out of memory"))
                }
                override fun cancelGeneration() {}
                override fun release() {}
            }
            val vm = SummaryViewModel(SummaryService(failingEngine, testDispatcher), preferencesManager)

            vm.generateSummary(chapterUrl, "Ch 1", listOf("text")) {}
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isGenerating)
            assertEquals("Ran out of memory", state.error)
            // The row that failed needs to be identifiable so only it shows the error.
            assertEquals(chapterUrl, state.activeChapterUrl)
            assertNull(state.currentSummary)
        }

    @Test
    fun `reportGenerationFailure surfaces a failure that happened before generation started`() {
        viewModel.reportGenerationFailure("https://example.com/ch9", "Could not load the chapter")

        val state = viewModel.uiState.value
        assertFalse(state.isGenerating)
        assertEquals("Could not load the chapter", state.error)
        assertEquals("https://example.com/ch9", state.activeChapterUrl)
    }
}
