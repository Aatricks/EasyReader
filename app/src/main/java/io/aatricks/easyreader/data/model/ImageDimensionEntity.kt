package io.aatricks.easyreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_dimension_cache")
data class ImageDimensionEntity(
    @PrimaryKey val imageUrl: String,
    val width: Int,
    val height: Int,
    val cachedAtMs: Long,
    val parserVersion: Int
)
