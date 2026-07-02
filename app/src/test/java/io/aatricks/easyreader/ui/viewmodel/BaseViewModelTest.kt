package io.aatricks.easyreader.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelTest {

    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    data class TestState(
        val isLoading: Boolean = false,
        val error: String? = null
    )

    class TestViewModel : BaseViewModel<TestState>(TestState()) {
        fun runWithStatus(
            handleLoading: Boolean = true,
            handleError: Boolean = true,
            block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
        ) {
            launchWithStatus(
                handleLoading = handleLoading,
                handleError = handleError,
                loadingState = { state, loading -> state.copy(isLoading = loading) },
                errorState = { state, err -> state.copy(error = err) },
                block = block
            )
        }
    }

    @Test
    fun `cancelled launchWithStatus does not set error state`() = runTest {
        val viewModel = TestViewModel()
        viewModel.runWithStatus {
            throw CancellationException("Cancelled")
        }
        
        // Assert error is not set because of cancellation
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `failing launchWithStatus sets error state`() = runTest {
        val viewModel = TestViewModel()
        viewModel.runWithStatus {
            throw RuntimeException("Real error")
        }
        
        // Assert error state is set on real failure
        assertEquals("Real error", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }
}
