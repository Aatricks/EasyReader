package io.aatricks.novelscraper.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aatricks.novelscraper.data.model.LibraryItem
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.util.TextUtils

/**
 * SharedPreferences wrapper for type-safe preferences access
 */
class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    private val gson = Gson()
    
    // Current URL
    var currentUrl: String?
        get() = prefs.getString(KEY_CURRENT_URL, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_URL, value).apply()
    
    // Current paragraphs
    fun saveParagraphs(paragraphs: List<String>) {
        val json = gson.toJson(paragraphs)
        prefs.edit().putString(KEY_PARAGRAPHS, json).apply()
    }
    
    fun loadParagraphs(): List<String> {
        val json = prefs.getString(KEY_PARAGRAPHS, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return try {
            gson.fromJson(json, type)
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
        val json = gson.toJson(items)
        prefs.edit().putString(KEY_LIBRARY_ITEMS, json).apply()
    }
    
    fun loadLibraryItems(): List<LibraryItem> {
        val json = prefs.getString(KEY_LIBRARY_ITEMS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<LibraryItem>>() {}.type
                val items: List<LibraryItem> = gson.fromJson(json, type)
                // Migration: ensure all fields are properly initialized even if missing in JSON
                items.map { item ->
                    // Use a temporary variable to handle potential nulls from Gson deserialization
                    // of non-nullable Kotlin fields
                    @Suppress("SENSELESS_COMPARISON")
                    val isInvalid = item.id == null ||
                                   item.title == null ||
                                   item.url == null ||
                                   item.chapterSummaries == null || 
                                   item.baseTitle == null || 
                                   item.readingMode == null ||
                                   item.baseNovelUrl == null ||
                                   item.sourceName == null ||
                                   @Suppress("SENSELESS_COMPARISON")
                                   (item.hasUpdates == null as Any?)
                    
                    if (isInvalid) {
                        val safeTitle = item.title ?: "Unknown"
                        val safeUrl = item.url ?: ""
                        // Reconstruct the item to ensure default values are used for missing fields
                        LibraryItem(
                            id = item.id ?: System.currentTimeMillis().toString(),
                            title = safeTitle,
                            url = safeUrl,
                            timestamp = item.timestamp,
                            type = item.type ?: ContentType.WEB,
                            progress = item.progress,
                            isCurrentlyReading = item.isCurrentlyReading,
                            isSelected = item.isSelected,
                            currentChapter = item.currentChapter ?: "",
                            currentChapterUrl = item.currentChapterUrl ?: "",
                            totalChapters = item.totalChapters,
                            contentType = item.contentType ?: ContentType.WEB,
                            dateAdded = item.dateAdded,
                            lastRead = item.lastRead,
                            isDownloading = item.isDownloading,
                            lastScrollPosition = item.lastScrollPosition,
                            lastReadIndex = item.lastReadIndex,
                            lastReadOffset = item.lastReadOffset,
                            hasUpdates = item.hasUpdates,
                            chapterSummaries = item.chapterSummaries ?: emptyMap(),
                            baseTitle = if (item.baseTitle == null || item.baseTitle.isEmpty()) 
                                TextUtils.extractBaseTitle(safeTitle, item.contentType ?: ContentType.WEB) 
                                else item.baseTitle,
                            readingMode = if (item.readingMode == null) io.aatricks.novelscraper.data.model.ReadingMode.VERTICAL else item.readingMode,
                            baseNovelUrl = item.baseNovelUrl ?: "",
                            sourceName = item.sourceName ?: ""
                        )
                    } else {
                        item
                    }
                }
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
        val json = gson.toJson(sources)
        prefs.edit().putString(KEY_COLLAPSED_SOURCES, json).apply()
    }

    fun loadCollapsedSources(): Set<String> {
        val json = prefs.getString(KEY_COLLAPSED_SOURCES, null) ?: return emptySet()
        val type = object : TypeToken<Set<String>>() {}.type
        return try {
            gson.fromJson(json, type)
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
    }
}
