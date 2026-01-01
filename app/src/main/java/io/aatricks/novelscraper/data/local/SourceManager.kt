package io.aatricks.novelscraper.data.local

import android.content.Context
import android.content.SharedPreferences
import io.aatricks.novelscraper.data.repository.source.NovelSource
import io.aatricks.novelscraper.data.repository.source.SmartScraperSource
import org.json.JSONArray
import org.json.JSONObject

class SourceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("custom_sources", Context.MODE_PRIVATE)

    fun addSource(url: String) {
        val sources = getSources().toMutableSet()
        sources.add(url)
        prefs.edit().putStringSet("sources", sources).apply()
    }

    fun getSources(): Set<String> {
        return prefs.getStringSet("sources", emptySet()) ?: emptySet()
    }

    fun removeSource(url: String) {
        val sources = getSources().toMutableSet()
        sources.remove(url)
        prefs.edit().putStringSet("sources", sources).apply()
    }

    fun getNovelSources(): List<NovelSource> {
        return getSources().map { SmartScraperSource(it) }
    }
}
