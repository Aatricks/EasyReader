package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.updater.AppUpdateManager
import io.aatricks.easyreader.updater.DownloadStatus
import io.aatricks.easyreader.updater.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
    
    private val appUpdateManager = mock<AppUpdateManager> {
        on { getAppVersionName() } doReturn "0.5.1"
    }

    private val preferencesManager = mock<PreferencesManager>()
    private val context = mock<android.content.Context>()

    private lateinit var viewModel: UpdateViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(preferencesManager.automaticUpdateChecksEnabled).doReturn(true)
        viewModel = UpdateViewModel(appUpdateManager, preferencesManager, context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state retrieves current version`() {
        assertEquals("0.5.1", viewModel.uiState.value.currentVersion)
        assertEquals(DownloadStatus.Idle, viewModel.uiState.value.downloadStatus)
        assertNull(viewModel.uiState.value.updateAvailable)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `checkForUpdates success updates state with new version`() = runTest {
        val newVersion = UpdateCheckResult.NewVersion(
            versionName = "0.5.2",
            changelog = "Bug fixes",
            downloadUrl = "https://example.com/apk",
            fileSize = 1000L
        )
        whenever(appUpdateManager.checkForUpdates()).doReturn(newVersion)

        viewModel.checkForUpdates()

        assertEquals(newVersion, viewModel.uiState.value.updateAvailable)
        assertNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isChecking)
        verify(preferencesManager).lastAppUpdateCheckTime = any()
    }

    @Test
    fun `checkForUpdates up to date resets new version`() = runTest {
        whenever(appUpdateManager.checkForUpdates()).doReturn(UpdateCheckResult.NoNewVersion)

        viewModel.checkForUpdates()

        assertNull(viewModel.uiState.value.updateAvailable)
        assertNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isChecking)
        verify(preferencesManager).lastAppUpdateCheckTime = any()
    }

    @Test
    fun `checkForUpdates error updates state with error`() = runTest {
        whenever(appUpdateManager.checkForUpdates()).doReturn(UpdateCheckResult.Error("API Limit reached"))

        viewModel.checkForUpdates()

        assertNull(viewModel.uiState.value.updateAvailable)
        assertEquals("API Limit reached", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isChecking)
    }

    @Test
    fun `checkForUpdatesIfNeeded skips checking if checked recently`() = runTest {
        whenever(preferencesManager.lastAppUpdateCheckTime).doReturn(System.currentTimeMillis() - 1000L)

        viewModel.checkForUpdatesIfNeeded()

        verify(appUpdateManager, never()).checkForUpdates()
    }

    @Test
    fun `checkForUpdatesIfNeeded skips checking when automatic checks are disabled`() = runTest {
        whenever(preferencesManager.automaticUpdateChecksEnabled).doReturn(false)
        viewModel = UpdateViewModel(appUpdateManager, preferencesManager, context)
        whenever(preferencesManager.lastAppUpdateCheckTime).doReturn(0L)

        viewModel.checkForUpdatesIfNeeded()

        verify(appUpdateManager, never()).checkForUpdates()
    }

    @Test
    fun `setAutomaticUpdateChecksEnabled persists and updates state`() {
        viewModel.setAutomaticUpdateChecksEnabled(false)

        verify(preferencesManager).automaticUpdateChecksEnabled = false
        assertEquals(false, viewModel.uiState.value.automaticUpdateChecksEnabled)
    }

    @Test
    fun `checkForUpdatesIfNeeded runs check if interval passed`() = runTest {
        val newVersion = UpdateCheckResult.NewVersion(
            versionName = "0.5.2",
            changelog = "Bug fixes",
            downloadUrl = "https://example.com/apk",
            fileSize = 1000L
        )
        whenever(preferencesManager.lastAppUpdateCheckTime).doReturn(0L)
        whenever(appUpdateManager.checkForUpdates()).doReturn(newVersion)

        viewModel.checkForUpdatesIfNeeded()

        verify(appUpdateManager).checkForUpdates()
        assertEquals(newVersion, viewModel.uiState.value.updateAvailable)
        verify(preferencesManager).lastAppUpdateCheckTime = any()
    }

    @Test
    fun `startDownload collects progress and success`() = runTest {
        val apkFile = mock<File>()
        val progress = DownloadStatus.Progress(500, 1000)
        val success = DownloadStatus.Success(apkFile)
        
        whenever(appUpdateManager.downloadUpdate(any(), any(), any())).doReturn(flowOf(progress, success))

        viewModel.startDownload("https://example.com/apk", "0.5.2", 1000L)

        assertEquals(success, viewModel.uiState.value.downloadStatus)
    }

    @Test
    fun `installApk delegates to appUpdateManager`() {
        val apkFile = mock<File>()
        
        viewModel.installApk(apkFile)
        
        verify(appUpdateManager).installApk(apkFile)
    }

    @Test
    fun `clearUpdateState resets state fields`() = runTest {
        val newVersion = UpdateCheckResult.NewVersion(
            versionName = "0.5.2",
            changelog = "Bug fixes",
            downloadUrl = "https://example.com/apk",
            fileSize = 1000L
        )
        whenever(appUpdateManager.checkForUpdates()).doReturn(newVersion)
        viewModel.checkForUpdates()
        
        viewModel.clearUpdateState()

        assertNull(viewModel.uiState.value.updateAvailable)
        assertEquals(DownloadStatus.Idle, viewModel.uiState.value.downloadStatus)
        assertNull(viewModel.uiState.value.error)
    }
}
