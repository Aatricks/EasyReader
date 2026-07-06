package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.ImageDimensionDao
import io.aatricks.easyreader.data.model.ImageDimensionEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageDimensionCacheRepository @Inject constructor(
    private val dao: ImageDimensionDao
) {
    companion object {
        /**
         * Bump when the upstream dimension-resolution code shape changes (e.g. parser swap,
         * sniff format change). Stale rows are filtered out on read and pruned at app start.
         */
        const val CURRENT_PARSER_VERSION: Int = 1

        const val DEFAULT_TTL_MS: Long = 90L * 24L * 60L * 60L * 1000L // 90 days
    }

    suspend fun getMany(urls: List<String>): Map<String, ImageDimensionEntity> {
        if (urls.isEmpty()) return emptyMap()
        return runCatching {
            dao.getMany(urls.distinct(), CURRENT_PARSER_VERSION).associateBy { it.imageUrl }
        }.getOrElse { emptyMap() }
    }

    suspend fun persist(imageUrl: String, width: Int, height: Int) {
        if (imageUrl.isBlank() || width <= 0 || height <= 0) return
        runCatching {
            dao.upsert(
                ImageDimensionEntity(
                    imageUrl = imageUrl,
                    width = width,
                    height = height,
                    cachedAtMs = System.currentTimeMillis(),
                    parserVersion = CURRENT_PARSER_VERSION
                )
            )
        }
    }

    /** @return true when the rows reached the DB (or there was nothing valid to write). */
    suspend fun persistAll(entries: List<Triple<String, Int, Int>>): Boolean {
        if (entries.isEmpty()) return true
        val now = System.currentTimeMillis()
        val rows = entries
            .filter { it.first.isNotBlank() && it.second > 0 && it.third > 0 }
            .map { (url, w, h) ->
                ImageDimensionEntity(
                    imageUrl = url,
                    width = w,
                    height = h,
                    cachedAtMs = now,
                    parserVersion = CURRENT_PARSER_VERSION
                )
            }
        if (rows.isEmpty()) return true
        return runCatching { dao.upsertAll(rows) }.isSuccess
    }

    suspend fun prune(ttlMs: Long = DEFAULT_TTL_MS) {
        val cutoff = System.currentTimeMillis() - ttlMs
        runCatching { dao.prune(cutoff, CURRENT_PARSER_VERSION) }
    }
}
