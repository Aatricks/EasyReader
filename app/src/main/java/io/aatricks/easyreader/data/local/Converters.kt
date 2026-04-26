package io.aatricks.easyreader.data.local

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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            ReadingMode.VERTICAL
        }
    }
}