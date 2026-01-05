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
    
    // Current title for tracking
    var currentTitle: String?
        get() = prefs.getString(KEY_CURRENT_TITLE, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_TITLE, value).apply()

    // Last update check time
    var lastUpdateCheckTime: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
    
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
        private const val KEY_CURRENT_TITLE = "current_title"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
