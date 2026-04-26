package io.aatricks.novelscraper.ui.viewmodel

import io.aatricks.novelscraper.data.repository.SummaryService
import io.aatricks.novelscraper.data.repository.summary.DisabledSummaryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var summaryEngine: DisabledSummaryEngine
    private lateinit var summaryService: SummaryService
    private lateinit var viewModel: SummaryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        summaryEngine = DisabledSummaryEngine()
        summaryService = SummaryService(summaryEngine)
        viewModel = SummaryViewModel(summaryService)
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
    fun `when generating summary with disabled engine, should set error state`() = runTest {
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
    fun `initializeSummaryService should fail gracefully with disabled engine`() = runTest {
        viewModel.initializeSummaryService()
        
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertFalse(state.isInitializing)
        assertEquals("AI Summarization is disabled in this build.", state.error)
    }
}
