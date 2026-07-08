package io.aatricks.easyreader.ui.screens.settings

import androidx.test.core.app.ApplicationProvider
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.ui.theme.AccentTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        preferencesManager = PreferencesManager(context)
        preferencesManager.clearAll()
        viewModel = SettingsViewModel(preferencesManager)
    }

    @Test
    fun `themeMode updates correctly`() = runBlocking {
        viewModel.setThemeMode("DARK")
        assertEquals("DARK", preferencesManager.themeMode)
        assertEquals("DARK", viewModel.appearanceSettings.first().themeMode)
    }

    @Test
    fun `dynamicColor updates correctly`() = runBlocking {
        viewModel.setDynamicColor(true)
        assertTrue(preferencesManager.dynamicColor)
        assertTrue(viewModel.appearanceSettings.first().dynamicColor)
    }

    @Test
    fun `accentTheme updates correctly`() = runBlocking {
        viewModel.setAccentTheme(AccentTheme.ROSE)
        assertEquals(AccentTheme.ROSE.name, preferencesManager.accentTheme)
        assertEquals(AccentTheme.ROSE.name, viewModel.readerSettings.first().accentTheme)
    }
}
