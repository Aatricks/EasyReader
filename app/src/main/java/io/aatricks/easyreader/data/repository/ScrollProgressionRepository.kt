package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.libraryDisplayTitle
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.seriesReadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class FinishedSeriesData(
    val title: String,
    val coverItem: LibraryItem?
)

@Singleton
class ScrollProgressionRepository(
    private val readingSessionDao: ReadingSessionDao,
    private val libraryRepository: LibraryRepository,
    private val preferencesManager: PreferencesManager,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        readingSessionDao: ReadingSessionDao,
        libraryRepository: LibraryRepository,
        preferencesManager: PreferencesManager
    ) : this(readingSessionDao, libraryRepository, preferencesManager, { System.currentTimeMillis() })

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val progression: StateFlow<ScrollProgression> = combine(
        readingSessionDao.observeTotals(),
        libraryRepository.libraryItems
    ) { totals, items ->
        val readingDayCount = readingSessionDao.getDistinctReadingDayCount()
        val sessions = readingSessionDao.getAllSessions()

        val finishedTitles = items.groupBy { it.libraryNovelKey() }
            .filter { (_, seriesItems) -> seriesReadingStatus(seriesItems) == SeriesReadingStatus.FINISHED }
            .keys

        val currentFinished = preferencesManager.scrollFinishedSeries
        val newFinished = currentFinished + finishedTitles
        if (newFinished.size > currentFinished.size) {
            preferencesManager.scrollFinishedSeries = newFinished
        }

        val prog = ScrollProgressionEngine.compute(
            ProgressionInput(
                totalActiveMillis = totals.totalActiveMillis,
                totalChaptersCompleted = totals.totalChaptersCompleted,
                readingDayCount = readingDayCount,
                finishedSeriesCount = newFinished.size,
                sessions = sessions,
                libraryItems = items,
                unlockedMilestones = preferencesManager.scrollUnlockedMilestones,
                nowMs = clock()
            )
        )

        val newlyUnlocked = prog.milestones.filter { it.unlockedAtMs != null }
        val currentUnlockedMap = preferencesManager.scrollUnlockedMilestones
        var mapChanged = false
        val updatedUnlockedMap = currentUnlockedMap.toMutableMap()
        for (m in newlyUnlocked) {
            if (!updatedUnlockedMap.containsKey(m.id)) {
                updatedUnlockedMap[m.id] = m.unlockedAtMs!!
                mapChanged = true
            }
        }
        if (mapChanged) {
            preferencesManager.scrollUnlockedMilestones = updatedUnlockedMap
        }

        prog
    }.stateIn(
        scope = repositoryScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ScrollProgression.EMPTY
    )

    val unseenMilestoneCount: Flow<Int> = progression.map { prog ->
        val seen = preferencesManager.scrollSeenMilestones
        prog.milestones.count { it.unlockedAtMs != null && it.id !in seen }
    }

    val finishedSeriesData: Flow<List<FinishedSeriesData>> = libraryRepository.libraryItems.map { items ->
        items.groupBy { it.libraryNovelKey() }
            .filter { (_, seriesItems) -> seriesReadingStatus(seriesItems) == SeriesReadingStatus.FINISHED }
            .map { (_, seriesItems) ->
                val firstItem = seriesItems.first()
                FinishedSeriesData(
                    title = firstItem.libraryDisplayTitle(),
                    // Any chapter row may carry the cover; rows are not ordered by completeness
                    coverItem = seriesItems.firstOrNull { it.coverImageUrl.isNotBlank() }
                )
            }
    }

    fun markAllMilestonesSeen() {
        val currentProg = progression.value
        val unlockedIds = currentProg.milestones.filter { it.unlockedAtMs != null }.map { it.id }.toSet()
        val seen = preferencesManager.scrollSeenMilestones
        preferencesManager.scrollSeenMilestones = seen + unlockedIds
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
