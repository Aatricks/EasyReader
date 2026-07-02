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
    val paragraphSpacing: Float,
    val readerTheme: String,
    val accentTheme: String
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

    // Held in a field so the SharedPreferences weak-ref doesn't drop it.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in READER_SETTINGS_KEYS) {
            _readerSettings.value = readReaderSettingsSnapshot()
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
        paragraphSpacing = prefs.getFloat(KEY_PARAGRAPH_SPACING, 1.0f),
        readerTheme = prefs.getString(KEY_READER_THEME, io.aatricks.easyreader.data.model.ReaderTheme.DARK.name)
            ?: io.aatricks.easyreader.data.model.ReaderTheme.DARK.name,
        accentTheme = prefs.getString(KEY_ACCENT_THEME, io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name)
            ?: io.aatricks.easyreader.ui.theme.AccentTheme.MOSS.name
    )
    
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

    var paragraphSpacing: Float
        get() = prefs.getFloat(KEY_PARAGRAPH_SPACING, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PARAGRAPH_SPACING, value).apply()

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
        paragraphSpacing: Float? = null,
        readerTheme: String? = null,
        accentTheme: String? = null
    ) {
        val editor = prefs.edit()
        fontSize?.let { editor.putFloat(KEY_FONT_SIZE, it) }
        lineHeight?.let { editor.putFloat(KEY_LINE_HEIGHT, it) }
        fontFamily?.let { editor.putString(KEY_FONT_FAMILY, it) }
        margins?.let { editor.putInt(KEY_MARGINS, it) }
        paragraphSpacing?.let { editor.putFloat(KEY_PARAGRAPH_SPACING, it) }
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
        
        // Reader Settings Keys
        private const val KEY_FONT_SIZE = "reader_font_size"
        private const val KEY_LINE_HEIGHT = "reader_line_height"
        private const val KEY_FONT_FAMILY = "reader_font_family"
        private const val KEY_MARGINS = "reader_margins"
        private const val KEY_PARAGRAPH_SPACING = "reader_paragraph_spacing"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_ACCENT_THEME = "accent_theme"

        private const val KEY_AI_SUMMARY_ENABLED = "ai_summary_enabled"
        private const val KEY_WEB_OFFLINE_PIPELINE_VERSION = "web_offline_pipeline_version"

        private val READER_SETTINGS_KEYS = setOf(
            KEY_FONT_SIZE,
            KEY_LINE_HEIGHT,
            KEY_FONT_FAMILY,
            KEY_MARGINS,
            KEY_PARAGRAPH_SPACING,
            KEY_READER_THEME,
            KEY_ACCENT_THEME
        )
    }
}
