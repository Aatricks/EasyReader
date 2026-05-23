package io.aatricks.easyreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aatricks.easyreader.data.model.ImageDimensionEntity

@Dao
interface ImageDimensionDao {
    @Query("""
        SELECT * FROM image_dimension_cache
        WHERE imageUrl IN (:urls) AND parserVersion = :parserVersion
    """)
    suspend fun getMany(urls: List<String>, parserVersion: Int): List<ImageDimensionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageDimensionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ImageDimensionEntity>)

    @Query("""
        DELETE FROM image_dimension_cache
        WHERE cachedAtMs < :cutoffMs OR parserVersion < :currentParserVersion
    """)
    suspend fun prune(cutoffMs: Long, currentParserVersion: Int)
}
