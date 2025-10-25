package io.aatricks.novelscraper.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aatricks.novelscraper.data.model.LibraryItem

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
                // Migration: ensure chapterSummaries is never null (for items persisted before the field was added)
                items.map { item ->
                    if (item.chapterSummaries == null) {
                        item.copy(chapterSummaries = emptyMap())
                    } else {
                        item
                    }
                }
            } catch (e: Exception) {
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
    }
}
