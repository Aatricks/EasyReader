package io.aatricks.easyreader.data.local

import android.content.Context
import android.content.SharedPreferences
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.util.TextUtils

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of all reader-facing preferences. Emitted on every change so the
 * reader UI can react to bulk updates (e.g. backup restore) without relying
 * on per-setter call sites.
 */
data class ReaderSettingsSnapshot(
    val fontSize: Float,
    val lineHeight: Float,
    val fontFamily: String,
    val margins: Int,
    val verticalMargins: Int,
    val paragraphSpacing: Float,
    val readerTheme: String,
    val accentTheme: String,
    val brightness: Float = 1.0f
)

data class AppearanceSettingsSnapshot(
    val themeMode: String,
    val dynamicColor: Boolean
)

/**
 * SharedPreferences wrapper for type-safe preferences access
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val _readerSettings = MutableStateFlow(readReaderSettingsSnapshot())

    /** Reactive view of every reader-facing preference. Emits on any mutation. */
    val readerSettings: StateFlow<ReaderSettingsSnapshot> = _readerSettings.asStateFlow()

    private val _appearanceSettings = MutableStateFlow(readAppearanceSettingsSnapshot())

    /** Reactive view of appearance settings. Emits on any mutation. */
    val appearanceSettings: StateFlow<AppearanceSettingsSnapshot> = _appearanceSettings.asStateFlow()

    // Held in a field so the SharedPreferences weak-ref doesn't drop it.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in READER_SETTINGS_KEYS) {
            _readerSettings.value = readReaderSettingsSnapshot()
        }
        if (key == null || key in APPEARANCE_SETTINGS_KEYS) {
            _appearanceSettings.value = readAppearanceSettingsSnapshot()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun readReaderSettingsSnapshot(): ReaderSettingsSnapshot = ReaderSettingsSnapshot(
        fontSize = prefs.getFloat(KEY_FONT_SIZE, 18f),
        lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, 1.5f),
        fontFamily = prefs.getString(KEY_FONT_FAMILY, "Default") ?: "Default",
        margins = prefs.getInt(KEY_MARGINS, 16),
        verticalMargins = prefs.getInt(KEY_VERTICAL_MARGINS, 0),
        paragraphSpacing = prefs.getFloat(KEY_PARAGRAPH_SPACING, 1.0f),
        readerTheme = prefs.getString(KEY_READER_THEME, io.aatricks.easyreader.data.model.ReaderTheme.DARK.name)
            ?: io.aatricks.easyreader.data.model.ReaderTheme.DARK.name,
        accentTheme = prefs.getString(KEY_ACCENT_THEME, io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name)
            ?: io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name,
        brightness = brightness
    )

    private fun readAppearanceSettingsSnapshot(): AppearanceSettingsSnapshot = AppearanceSettingsSnapshot(
        themeMode = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM",
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
    )

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
    
    // Last-read chapter URL, mirrored on every successful chapter load so cold launch can
    // restore the reader without waiting for the Room currently-reading query.
    var lastReadUrl: String?
        get() = prefs.getString(KEY_LAST_READ_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_READ_URL, value).apply()

    var lastReadLibraryItemId: String?
        get() = prefs.getString(KEY_LAST_READ_LIBRARY_ITEM_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_READ_LIBRARY_ITEM_ID, value).apply()

    fun batchUpdateLastRead(url: String?, libraryItemId: String?) {
        prefs.edit()
            .putString(KEY_LAST_READ_URL, url)
            .putString(KEY_LAST_READ_LIBRARY_ITEM_ID, libraryItemId)
            .apply()
    }
    
    // Library items (legacy SharedPreferences store — read only, for one-time migration to Room)
    fun loadLibraryItems(): List<LibraryItem> {
        val jsonString = prefs.getString(KEY_LIBRARY_ITEMS, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString<List<LibraryItem>>(jsonString)
            } catch (e: Exception) {
                android.util.Log.e("PreferencesManager", "Failed to load library items", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // Collapsed sources
    fun saveCollapsedSources(sources: Set<String>) {
        val jsonString = json.encodeToString(sources)
        prefs.edit().putString(KEY_COLLAPSED_SOURCES, jsonString).apply()
    }

    fun loadCollapsedSources(): Set<String> {
        val jsonString = prefs.getString(KEY_COLLAPSED_SOURCES, null) ?: return emptySet()
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    // Last update check time
    var lastUpdateCheckTime: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    // Last app update check time
    var lastAppUpdateCheckTime: Long
        get() = prefs.getLong(KEY_LAST_APP_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_APP_UPDATE_CHECK, value).apply()

    var automaticUpdateChecksEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_UPDATE_CHECKS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOMATIC_UPDATE_CHECKS_ENABLED, value).apply()
        
    // Reader Settings
    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 18f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_LINE_HEIGHT, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "Default") ?: "Default"
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var margins: Int
        get() = prefs.getInt(KEY_MARGINS, 16)
        set(value) = prefs.edit().putInt(KEY_MARGINS, value).apply()

    var verticalMargins: Int
        get() = prefs.getInt(KEY_VERTICAL_MARGINS, 0)
        set(value) = prefs.edit().putInt(KEY_VERTICAL_MARGINS, value).apply()

    var paragraphSpacing: Float
        get() = prefs.getFloat(KEY_PARAGRAPH_SPACING, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PARAGRAPH_SPACING, value).apply()

    var brightness: Float
        get() = prefs.getFloat(KEY_BRIGHTNESS, MAX_READER_BRIGHTNESS)
            .coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
        set(value) = prefs.edit()
            .putFloat(KEY_BRIGHTNESS, value.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS))
            .apply()

    var readerTheme: String
        get() = prefs.getString(KEY_READER_THEME, io.aatricks.easyreader.data.model.ReaderTheme.DARK.name) 
            ?: io.aatricks.easyreader.data.model.ReaderTheme.DARK.name
        set(value) = prefs.edit().putString(KEY_READER_THEME, value).apply()

    var accentTheme: String
        get() = prefs.getString(KEY_ACCENT_THEME, io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name)
            ?: io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name
        set(value) = prefs.edit().putString(KEY_ACCENT_THEME, value).apply()

    // Opt-in for AI summary model. False by default so the model is never
    // downloaded unless the user explicitly enables the feature.
    var aiSummaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SUMMARY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_SUMMARY_ENABLED, value).apply()

    var webOfflinePipelineVersion: Int
        get() = prefs.getInt(KEY_WEB_OFFLINE_PIPELINE_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_WEB_OFFLINE_PIPELINE_VERSION, value).apply()

    private val _scrollGamificationEnabled by lazy {
        MutableStateFlow(prefs.getBoolean(KEY_SCROLL_GAMIFICATION_ENABLED, true))
    }
    val scrollGamificationEnabledFlow: StateFlow<Boolean> by lazy { _scrollGamificationEnabled.asStateFlow() }

    var scrollGamificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_GAMIFICATION_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SCROLL_GAMIFICATION_ENABLED, value).apply()
            _scrollGamificationEnabled.value = value
        }

    var scrollHistorySeeded: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_HISTORY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_HISTORY_SEEDED, value).apply()

    var scrollFinishedSeries: Set<String>
        get() {
            val str = prefs.getString(KEY_SCROLL_FINISHED_SERIES, null) ?: return emptySet()
            return try { json.decodeFromString(str) } catch (e: Exception) { emptySet() }
        }
        set(value) = prefs.edit().putString(KEY_SCROLL_FINISHED_SERIES, json.encodeToString(value)).apply()

    var scrollUnlockedMilestones: Map<String, Long>
        get() {
            val str = prefs.getString(KEY_SCROLL_UNLOCKED_MILESTONES, null) ?: return emptyMap()
            return try { json.decodeFromString(str) } catch (e: Exception) { emptyMap() }
        }
        set(value) = prefs.edit().putString(KEY_SCROLL_UNLOCKED_MILESTONES, json.encodeToString(value)).apply()

    var scrollSeenMilestones: Set<String>
        get() {
            val str = prefs.getString(KEY_SCROLL_SEEN_MILESTONES, null) ?: return emptySet()
            return try { json.decodeFromString(str) } catch (e: Exception) { emptySet() }
        }
        set(value) = prefs.edit().putString(KEY_SCROLL_SEEN_MILESTONES, json.encodeToString(value)).apply()

    // Clear all preferences

    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Batch update multiple reader settings in a single SharedPreferences transaction.
     */
    fun batchUpdateReaderSettings(
        fontSize: Float? = null,
        lineHeight: Float? = null,
        fontFamily: String? = null,
        margins: Int? = null,
        verticalMargins: Int? = null,
        paragraphSpacing: Float? = null,
        brightness: Float? = null,
        readerTheme: String? = null,
        accentTheme: String? = null
    ) {
        val editor = prefs.edit()
        fontSize?.let { editor.putFloat(KEY_FONT_SIZE, it) }
        lineHeight?.let { editor.putFloat(KEY_LINE_HEIGHT, it) }
        fontFamily?.let { editor.putString(KEY_FONT_FAMILY, it) }
        margins?.let { editor.putInt(KEY_MARGINS, it) }
        verticalMargins?.let { editor.putInt(KEY_VERTICAL_MARGINS, it) }
        paragraphSpacing?.let { editor.putFloat(KEY_PARAGRAPH_SPACING, it) }
        brightness?.let {
            editor.putFloat(KEY_BRIGHTNESS, it.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS))
        }
        readerTheme?.let { editor.putString(KEY_READER_THEME, it) }
        accentTheme?.let { editor.putString(KEY_ACCENT_THEME, it) }
        editor.apply()
    }
    
    companion object {
        private const val PREFS_NAME = "novel_scraper_prefs"
        
        private const val KEY_LAST_READ_URL = "last_read_url"
        private const val KEY_LAST_READ_LIBRARY_ITEM_ID = "last_read_library_item_id"
        private const val KEY_LIBRARY_ITEMS = "library_items"
        private const val KEY_COLLAPSED_SOURCES = "collapsed_sources"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LAST_APP_UPDATE_CHECK = "last_app_update_check"
        private const val KEY_AUTOMATIC_UPDATE_CHECKS_ENABLED = "automatic_update_checks_enabled"
        
        // Reader Settings Keys
        private const val KEY_FONT_SIZE = "reader_font_size"
        private const val KEY_LINE_HEIGHT = "reader_line_height"
        private const val KEY_FONT_FAMILY = "reader_font_family"
        private const val KEY_MARGINS = "reader_margins"
        private const val KEY_VERTICAL_MARGINS = "reader_vertical_margins"
        private const val KEY_PARAGRAPH_SPACING = "reader_paragraph_spacing"
        private const val KEY_BRIGHTNESS = "reader_brightness"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_ACCENT_THEME = "accent_theme"

        private const val KEY_AI_SUMMARY_ENABLED = "ai_summary_enabled"
        private const val KEY_WEB_OFFLINE_PIPELINE_VERSION = "web_offline_pipeline_version"
        private const val KEY_SCROLL_HISTORY_SEEDED = "scroll_history_seeded"
        private const val KEY_SCROLL_GAMIFICATION_ENABLED = "scroll_gamification_enabled"
        private const val KEY_SCROLL_FINISHED_SERIES = "scroll_finished_series"
        private const val KEY_SCROLL_UNLOCKED_MILESTONES = "scroll_unlocked_milestones"
        private const val KEY_SCROLL_SEEN_MILESTONES = "scroll_seen_milestones"

        // Appearance Settings Keys
        private const val KEY_THEME_MODE = "appearance_theme_mode"
        private const val KEY_DYNAMIC_COLOR = "appearance_dynamic_color"

        private val READER_SETTINGS_KEYS = setOf(
            KEY_FONT_SIZE,
            KEY_LINE_HEIGHT,
            KEY_FONT_FAMILY,
            KEY_MARGINS,
            KEY_VERTICAL_MARGINS,
            KEY_PARAGRAPH_SPACING,
            KEY_BRIGHTNESS,
            KEY_READER_THEME,
            KEY_ACCENT_THEME
        )

        private val APPEARANCE_SETTINGS_KEYS = setOf(
            KEY_THEME_MODE,
            KEY_DYNAMIC_COLOR
        )

        private const val MIN_READER_BRIGHTNESS = 0.1f
        private const val MAX_READER_BRIGHTNESS = 1.0f
    }
}
