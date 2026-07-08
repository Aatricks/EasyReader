package io.aatricks.easyreader.ui.screens.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.local.AppearanceSettingsSnapshot
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReaderSettingsSnapshot
import io.aatricks.easyreader.ui.theme.AccentTheme
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val appearanceSettings: StateFlow<AppearanceSettingsSnapshot> = preferencesManager.appearanceSettings
    val readerSettings: StateFlow<ReaderSettingsSnapshot> = preferencesManager.readerSettings

    fun setThemeMode(themeMode: String) {
        preferencesManager.themeMode = themeMode
    }

    fun setDynamicColor(enabled: Boolean) {
        preferencesManager.dynamicColor = enabled
    }

    fun setAccentTheme(accentTheme: AccentTheme) {
        preferencesManager.accentTheme = accentTheme.name
    }
}
