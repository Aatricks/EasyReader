package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.MilestoneState
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.model.SeriesReadingStatus
import io.aatricks.easyreader.data.model.libraryNovelKey
import io.aatricks.easyreader.data.model.seriesReadingStatus
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.pow

data class ProgressionInput(
    val totalActiveMillis: Long,
    val totalChaptersCompleted: Int,
    val readingDayCount: Int,
    val finishedSeriesCount: Int,
    val sessions: List<ReadingSessionEntity>,
    val libraryItems: List<LibraryItem>,
    val unlockedMilestones: Map<String, Long>,
    val nowMs: Long,
    val timeZone: TimeZone = TimeZone.getDefault()
)

object ScrollProgressionEngine {
    const val XP_PER_MINUTE = 1L
    const val XP_PER_CHAPTER = 10L
    const val XP_PER_SERIES = 150L
    const val XP_PER_DAY = 5L

    private const val MILESTONE_HUNDRED_CHAPTERS = 100
    private const val MILESTONE_THOUSAND_CHAPTERS = 1000
    private const val MILESTONE_TEN_HOURS_MS = 10L * 3600_000L
    private const val MILESTONE_HUNDRED_HOURS_MS = 100L * 3600_000L
    private const val MILESTONE_TEN_SERIES = 10
    private const val MILESTONE_THIRTY_DAYS = 30
    private const val HOUR_FOUR = 4
    private const val MILESTONE_MARATHON_MS = 3L * 3600_000L
    private const val MILESTONE_EPIC_CHAPTERS = 500

    private const val LEVEL_BASE_XP = 100.0
    private const val LEVEL_POWER = 1.5

    private const val RANK_INK_STUDENT_LEVEL = 5
    private const val RANK_CHRONICLER_LEVEL = 10
    private const val RANK_COURT_PAINTER_LEVEL = 18
    private const val RANK_INK_SAGE_LEVEL = 28
    private const val RANK_MASTER_OF_SCROLLS_LEVEL = 40
    private const val RANK_EMAKIMONO_MASTER_LEVEL = 55
    private const val MILLIS_PER_MINUTE = 60_000L

    data class MilestoneDefinition(
        val id: String,
        val name: String,
        val hidden: Boolean,
        val isSatisfied: (input: ProgressionInput) -> Boolean
    )

    val MILESTONES = listOf(
        MilestoneDefinition("first_chapter", "First Steps", false) { input -> input.totalChaptersCompleted >= 1 },
        MilestoneDefinition("first_series", "First Seal", false) { input -> input.finishedSeriesCount >= 1 },
        MilestoneDefinition("chapters_100", "Hundred Chapters", false) { input -> 
            input.totalChaptersCompleted >= MILESTONE_HUNDRED_CHAPTERS 
        },
        MilestoneDefinition("chapters_1000", "Thousand Chapters", false) { input -> 
            input.totalChaptersCompleted >= MILESTONE_THOUSAND_CHAPTERS 
        },
        MilestoneDefinition("hours_10", "Ten Hours of Ink", false) { input -> 
            input.totalActiveMillis >= MILESTONE_TEN_HOURS_MS 
        },
        MilestoneDefinition("hours_100", "Hundred Hours of Ink", false) { input -> 
            input.totalActiveMillis >= MILESTONE_HUNDRED_HOURS_MS 
        },
        MilestoneDefinition("series_10", "Ten Scrolls Sealed", false) { input -> 
            input.finishedSeriesCount >= MILESTONE_TEN_SERIES 
        },
        MilestoneDefinition("days_30", "Thirty Reading Days", false) { input -> 
            input.readingDayCount >= MILESTONE_THIRTY_DAYS 
        },
        MilestoneDefinition("night_reader", "Night Reading", true) { input ->
            input.sessions.any { session ->
                if (session.seeded) false else {
                    val cal = Calendar.getInstance(input.timeZone)
                    cal.timeInMillis = session.startedAt
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    hour in 0..HOUR_FOUR
                }
            }
        },
        MilestoneDefinition("marathon", "Long Immersion", true) { input ->
            input.sessions.any { it.activeMillis >= MILESTONE_MARATHON_MS }
        },
        MilestoneDefinition("epic_series", "Epic Journey", true) { input ->
            input.libraryItems.groupBy { it.libraryNovelKey() }.values.any { seriesItems ->
                seriesReadingStatus(seriesItems) == SeriesReadingStatus.FINISHED &&
                    seriesItems.maxOfOrNull { it.totalChapters } ?: 0 >= MILESTONE_EPIC_CHAPTERS
            }
        }
    )

    fun getRequiredXpForLevel(level: Int): Long {
        if (level <= 0) return 0L
        return floor(LEVEL_BASE_XP * level.toDouble().pow(LEVEL_POWER)).toLong()
    }

    fun computeLevel(totalXp: Long): Int {
        var level = 0
        while (getRequiredXpForLevel(level + 1) <= totalXp) {
            level++
        }
        return level
    }

    fun getRankName(level: Int): String {
        return when {
            level >= RANK_EMAKIMONO_MASTER_LEVEL -> "Emakimono Master"
            level >= RANK_MASTER_OF_SCROLLS_LEVEL -> "Master of Scrolls"
            level >= RANK_INK_SAGE_LEVEL -> "Ink Sage"
            level >= RANK_COURT_PAINTER_LEVEL -> "Court Painter"
            level >= RANK_CHRONICLER_LEVEL -> "Chronicler"
            level >= RANK_INK_STUDENT_LEVEL -> "Ink Student"
            else -> "Apprentice Scribe"
        }
    }

    fun compute(input: ProgressionInput): ScrollProgression {
        val timeXp = (input.totalActiveMillis / MILLIS_PER_MINUTE) * XP_PER_MINUTE
        val chapterXp = input.totalChaptersCompleted * XP_PER_CHAPTER
        val seriesXp = input.finishedSeriesCount * XP_PER_SERIES
        val daysXp = input.readingDayCount * XP_PER_DAY
        val totalXp = timeXp + chapterXp + seriesXp + daysXp

        val level = computeLevel(totalXp)
        val currentLevelXp = getRequiredXpForLevel(level)
        val nextLevelXp = getRequiredXpForLevel(level + 1)

        val xpIntoLevel = totalXp - currentLevelXp
        val xpToNextLevel = nextLevelXp - totalXp
        val rankName = getRankName(level)

        val milestones = MILESTONES.map { def ->
            val previouslyUnlockedAt = input.unlockedMilestones[def.id]
            val isNewlySatisfied = previouslyUnlockedAt == null && def.isSatisfied(input)
            MilestoneState(
                id = def.id,
                name = def.name,
                hidden = def.hidden,
                unlockedAtMs = previouslyUnlockedAt ?: if (isNewlySatisfied) input.nowMs else null
            )
        }

        return ScrollProgression(
            totalXp = totalXp,
            level = level,
            xpIntoLevel = xpIntoLevel,
            xpToNextLevel = xpToNextLevel,
            rankName = rankName,
            milestones = milestones,
            totalActiveMillis = input.totalActiveMillis,
            totalChaptersCompleted = input.totalChaptersCompleted,
            finishedSeriesCount = input.finishedSeriesCount,
            readingDayCount = input.readingDayCount
        )
    }
}
