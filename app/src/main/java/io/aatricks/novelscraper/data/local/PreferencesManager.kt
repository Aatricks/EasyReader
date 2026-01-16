package io.aatricks.novelscraper.data.local

import android.content.Context
import android.content.SharedPreferences
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.util.TextUtils

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
    
    // Current URL
    var currentUrl: String?
        get() = prefs.getString(KEY_CURRENT_URL, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_URL, value).apply()
    
    // Current paragraphs
    fun saveParagraphs(paragraphs: List<String>) {
        val jsonString = json.encodeToString(paragraphs)
        prefs.edit().putString(KEY_PARAGRAPHS, jsonString).apply()
    }
    
    fun loadParagraphs(): List<String> {
        val jsonString = prefs.getString(KEY_PARAGRAPHS, null) ?: return emptyList()
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Scroll position
    var scrollPosition: Int
        get() = prefs.getInt(KEY_SCROLL_POSITION, 0)
        set(value) = prefs.edit().putInt(KEY_SCROLL_POSITION, value).apply()
    
    // Library items
    fun saveLibraryItems(items: List<LibraryItem>) {
        val jsonString = json.encodeToString(items)
        prefs.edit().putString(KEY_LIBRARY_ITEMS, jsonString).apply()
    }
    
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
    
    // Current title for tracking
    var currentTitle: String?
        get() = prefs.getString(KEY_CURRENT_TITLE, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_TITLE, value).apply()

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
        get() = prefs.getString(KEY_READER_THEME, io.aatricks.novelscraper.data.model.ReaderTheme.DARK.name) 
            ?: io.aatricks.novelscraper.data.model.ReaderTheme.DARK.name
        set(value) = prefs.edit().putString(KEY_READER_THEME, value).apply()
    
    var ignoreSslErrors: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_SSL_ERRORS, false)
        set(value) = prefs.edit().putBoolean(KEY_IGNORE_SSL_ERRORS, value).apply()
    
    // Clear all preferences
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    // Clear specific data
    fun clearContent() {
        prefs.edit()
            .remove(KEY_PARAGRAPHS)
            .remove(KEY_SCROLL_POSITION)
            .apply()
    }
    
    companion object {
        private const val PREFS_NAME = "novel_scraper_prefs"
        
        private const val KEY_CURRENT_URL = "current_url"
        private const val KEY_PARAGRAPHS = "paragraphs"
        private const val KEY_SCROLL_POSITION = "scroll_position"
        private const val KEY_LIBRARY_ITEMS = "library_items"
        private const val KEY_COLLAPSED_SOURCES = "collapsed_sources"
        private const val KEY_CURRENT_TITLE = "current_title"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        
        // Reader Settings Keys
        private const val KEY_FONT_SIZE = "reader_font_size"
        private const val KEY_LINE_HEIGHT = "reader_line_height"
        private const val KEY_FONT_FAMILY = "reader_font_family"
        private const val KEY_MARGINS = "reader_margins"
        private const val KEY_PARAGRAPH_SPACING = "reader_paragraph_spacing"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_IGNORE_SSL_ERRORS = "ignore_ssl_errors"
    }
}
