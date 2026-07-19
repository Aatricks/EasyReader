package io.aatricks.easyreader.data.model

data class MilestoneState(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val unlockedAtMs: Long?
)

data class ScrollProgression(
    val totalXp: Long,
    val level: Int,
    val xpIntoLevel: Long,
    val xpToNextLevel: Long,
    val rankName: String,
    val milestones: List<MilestoneState>,
    val totalActiveMillis: Long,
    val totalChaptersCompleted: Int,
    val finishedSeriesCount: Int,
    val readingDayCount: Int
) {
    companion object {
        val EMPTY = ScrollProgression(
            totalXp = 0L,
            level = 0,
            xpIntoLevel = 0L,
            xpToNextLevel = 0L,
            rankName = "", // Will be filled by engine, but for raw empty it could be empty
            milestones = emptyList(),
            totalActiveMillis = 0L,
            totalChaptersCompleted = 0,
            finishedSeriesCount = 0,
            readingDayCount = 0
        )
    }
}
