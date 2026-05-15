package io.aatricks.easyreader.data.local

import android.util.Log
import androidx.room.TypeConverter
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ReadingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            Log.w(TAG, "toStringMap parse failed; returning empty map", e)
            emptyMap()
        }
    }

    @TypeConverter
    fun fromContentType(value: ContentType): String {
        return value.name
    }

    @TypeConverter
    fun toContentType(value: String): ContentType {
        return try {
            ContentType.valueOf(value)
        } catch (e: Exception) {
            Log.w(TAG, "toContentType parse failed for '$value'; defaulting to WEB", e)
            ContentType.WEB
        }
    }

    @TypeConverter
    fun fromReadingMode(value: ReadingMode): String {
        return value.name
    }

    @TypeConverter
    fun toReadingMode(value: String): ReadingMode {
        return try {
            ReadingMode.valueOf(value)
        } catch (e: Exception) {
            Log.w(TAG, "toReadingMode parse failed for '$value'; defaulting to VERTICAL", e)
            ReadingMode.VERTICAL
        }
    }

    private companion object {
        private const val TAG = "Converters"
    }
}
