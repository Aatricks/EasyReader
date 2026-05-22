package io.aatricks.easyreader.testutil

import io.aatricks.easyreader.data.local.ImageDimensionDao
import io.aatricks.easyreader.data.model.ImageDimensionEntity
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository

/** In-memory `ImageDimensionDao` so tests don't have to spin up Room. */
class FakeImageDimensionDao : ImageDimensionDao {
    private val store = mutableMapOf<String, ImageDimensionEntity>()

    override suspend fun getMany(urls: List<String>, parserVersion: Int): List<ImageDimensionEntity> {
        val set = urls.toHashSet()
        return store.values.filter { it.imageUrl in set && it.parserVersion == parserVersion }
    }

    override suspend fun upsert(entity: ImageDimensionEntity) {
        store[entity.imageUrl] = entity
    }

    override suspend fun upsertAll(entities: List<ImageDimensionEntity>) {
        entities.forEach { store[it.imageUrl] = it }
    }

    override suspend fun prune(cutoffMs: Long, currentParserVersion: Int) {
        store.values
            .filter { it.cachedAtMs < cutoffMs || it.parserVersion < currentParserVersion }
            .map { it.imageUrl }
            .forEach { store.remove(it) }
    }
}

fun fakeImageDimensionCacheRepository(): ImageDimensionCacheRepository =
    ImageDimensionCacheRepository(FakeImageDimensionDao())
