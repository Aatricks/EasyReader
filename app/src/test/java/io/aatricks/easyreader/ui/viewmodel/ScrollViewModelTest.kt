package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.repository.ScrollProgressionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

import kotlinx.coroutines.test.StandardTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ScrollProgressionRepository
    private lateinit var sessionTracker: ReadingSessionTracker
    private lateinit var viewModel: ScrollViewModel

    private val completionEventsFlow = MutableSharedFlow<Int>()
    private val progressionFlow = MutableStateFlow(ScrollProgression.EMPTY)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        sessionTracker = mock()

        whenever(repository.progression).thenReturn(progressionFlow)
        whenever(repository.unseenMilestoneCount).thenReturn(flowOf(0))
        whenever(sessionTracker.completionEvents).thenReturn(completionEventsFlow)

        viewModel = ScrollViewModel(repository, sessionTracker)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `notice lifecycle - sets on completion and clears after delay`() = runTest(testDispatcher) {
        progressionFlow.value = ScrollProgression.EMPTY.copy(rankName = "Apprentice Scribe")
        testDispatcher.scheduler.runCurrent() // wait for init to collect

        assertNull(viewModel.xpNotice.value)

        completionEventsFlow.emit(1)
        testDispatcher.scheduler.runCurrent()

        assertEquals("+10 · Apprentice Scribe", viewModel.xpNotice.value)

        // Advance just before clear
        advanceTimeBy(2499)
        testDispatcher.scheduler.runCurrent()
        assertEquals("+10 · Apprentice Scribe", viewModel.xpNotice.value)

        // Advance to clear
        advanceTimeBy(1)
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.xpNotice.value)
    }

    @Test
    fun `markMilestonesSeen delegates to repository`() {
        viewModel.markMilestonesSeen()
        verify(repository).markAllMilestonesSeen()
    }
}
