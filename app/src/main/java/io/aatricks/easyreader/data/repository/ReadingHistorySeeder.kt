package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import io.aatricks.easyreader.data.model.hasFinishedProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingHistorySeeder @Inject constructor(
    private val libraryDao: LibraryDao,
    private val readingSessionDao: ReadingSessionDao,
    private val preferencesManager: PreferencesManager
) {
    suspend fun seedIfNeeded() {
        if (!preferencesManager.scrollGamificationEnabled) return
        if (preferencesManager.scrollHistorySeeded) {
            return
        }

        if (!readingSessionDao.hasAnySessions()) {
            val allItems = libraryDao.getAllItemsDirect()
            val startedItems = allItems.filter { it.lastRead > 0 && it.isStarted() }
            if (startedItems.isNotEmpty()) {
                seedFromItems(startedItems)
            }
        }
        preferencesManager.scrollHistorySeeded = true
    }

    private suspend fun seedFromItems(items: List<LibraryItem>) {
        val grouped = items.groupBy { item ->
            val novelKey = item.baseTitle
            val utcDay = item.lastRead / MILLIS_PER_DAY
            novelKey to utcDay
        }

        val seededSessions = grouped.map { (key, chapters) ->
            val (novelKey, utcDay) = key
            val dayStart = utcDay * MILLIS_PER_DAY
            val chaptersCompletedCount = chapters.count { it.hasFinishedProgress() }
            val estimatedActive = chapters.size * FIVE_MINUTES_MILLIS
            val activeMillis = kotlin.math.min(estimatedActive, FOUR_HOURS_MILLIS)
            val endedAt = dayStart + activeMillis

            ReadingSessionEntity(
                novelKey = novelKey,
                startedAt = dayStart,
                endedAt = endedAt,
                activeMillis = activeMillis,
                chaptersCompleted = chaptersCompletedCount,
                seeded = true
            )
        }

        readingSessionDao.insertAll(seededSessions)
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val FIVE_MINUTES_MILLIS = 300_000L
        private const val FOUR_HOURS_MILLIS = 14_400_000L
    }
}
