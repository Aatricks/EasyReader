package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ScrollProgressionEngineTest {

    @Test
    fun `curve boundaries map exactly`() {
        // level = largest n >= 0 with floor(100.0 * n^1.5) <= totalXp
        // Level 0 requires 0
        assertEquals(0, ScrollProgressionEngine.computeLevel(0))
        assertEquals(0, ScrollProgressionEngine.computeLevel(99))

        // Level 1 requires floor(100 * 1) = 100
        assertEquals(1, ScrollProgressionEngine.computeLevel(100))
        assertEquals(1, ScrollProgressionEngine.computeLevel(281))

        // Level 2 requires floor(100 * 2^1.5) = floor(100 * 2.8284) = 282
        assertEquals(2, ScrollProgressionEngine.computeLevel(282))
        assertEquals(2, ScrollProgressionEngine.computeLevel(518))
        
        // Level 3 requires floor(100 * 3^1.5) = floor(100 * 5.196) = 519
        assertEquals(3, ScrollProgressionEngine.computeLevel(519))
    }

    @Test
    fun `rank mapping changes at thresholds`() {
        assertEquals("Apprentice Scribe", ScrollProgressionEngine.getRankName(0))
        assertEquals("Apprentice Scribe", ScrollProgressionEngine.getRankName(4))
        assertEquals("Ink Student", ScrollProgressionEngine.getRankName(5))
        assertEquals("Ink Student", ScrollProgressionEngine.getRankName(9))
        assertEquals("Chronicler", ScrollProgressionEngine.getRankName(10))
        assertEquals("Chronicler", ScrollProgressionEngine.getRankName(17))
        assertEquals("Court Painter", ScrollProgressionEngine.getRankName(18))
        assertEquals("Court Painter", ScrollProgressionEngine.getRankName(27))
        assertEquals("Ink Sage", ScrollProgressionEngine.getRankName(28))
        assertEquals("Ink Sage", ScrollProgressionEngine.getRankName(39))
        assertEquals("Master of Scrolls", ScrollProgressionEngine.getRankName(40))
        assertEquals("Master of Scrolls", ScrollProgressionEngine.getRankName(54))
        assertEquals("Emakimono Master", ScrollProgressionEngine.getRankName(55))
        assertEquals("Emakimono Master", ScrollProgressionEngine.getRankName(100))
    }

    @Test
    fun `xp formula arithmetic`() {
        val prog = ScrollProgressionEngine.compute(
            ProgressionInput(
                totalActiveMillis = 120_000L, // 2 mins = 2 XP
                totalChaptersCompleted = 3,   // 3 * 10 = 30 XP
                readingDayCount = 4,          // 4 * 5 = 20 XP
                finishedSeriesCount = 2,      // 2 * 150 = 300 XP
                sessions = emptyList(),
                libraryItems = emptyList(),
                unlockedMilestones = emptyMap(),
                nowMs = 0L
            )
        )
        // 2 + 30 + 20 + 300 = 352 XP
        assertEquals(352L, prog.totalXp)
        assertEquals(2, prog.level) // 352 >= 282 (level 2), but < 519 (level 3)
        assertEquals(352L - 282L, prog.xpIntoLevel)
        assertEquals(519L - 352L, prog.xpToNextLevel)
    }

    @Test
    fun `milestone predicates satisfied`() {
        val baseItem = LibraryItem(
            id = "1", title = "T", url = "U", contentType = ContentType.WEB,
            dateAdded = 0L, lastRead = 0L, currentChapter = "1", baseTitle = "T"
        )
        
        val finishedItem = baseItem.copy(
            id = "2", title = "T2", baseTitle = "T2", progress = 100, totalChapters = 500, currentChapter = "500"
        )
        
        val session1 = ReadingSessionEntity(
            novelKey = "1", startedAt = 0L, endedAt = 0L, activeMillis = 3 * 3600_000L, chaptersCompleted = 10
        )

        // TimeZone where 0 ms epoch is 00:00 (GMT)
        val tz = TimeZone.getTimeZone("GMT")

        val prog = ScrollProgressionEngine.compute(
            ProgressionInput(
                totalActiveMillis = 100 * 3600_000L,
                totalChaptersCompleted = 1000,
                readingDayCount = 30,
                finishedSeriesCount = 10,
                sessions = listOf(session1),
                libraryItems = listOf(finishedItem),
                unlockedMilestones = emptyMap(),
                nowMs = 123L,
                timeZone = tz
            )
        )

        val unlocked = prog.milestones.filter { it.unlockedAtMs != null }.map { it.id }.toSet()
        val expected = setOf(
            "first_chapter", "first_series", "chapters_100", "chapters_1000",
            "hours_10", "hours_100", "series_10", "days_30",
            "night_reader", "marathon", "epic_series"
        )
        assertEquals(expected, unlocked)
        
        // Ensure timestamp applied
        assertTrue(prog.milestones.all { if (it.id in expected) it.unlockedAtMs == 123L else true })
    }
    
    @Test
    fun `epic_series requires 500 chapters and finished status`() {
        val baseItem = LibraryItem(
            id = "1", title = "T", url = "U", contentType = ContentType.WEB,
            dateAdded = 0L, lastRead = 0L, currentChapter = "1", baseTitle = "T", progress = 100
        )
        
        // 499 chapters -> not epic
        val item499 = baseItem.copy(totalChapters = 499)
        var prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, emptyList(), listOf(item499), emptyMap(), 0L)
        )
        assertFalse(prog.milestones.find { it.id == "epic_series" }?.unlockedAtMs != null)
        
        // 500 chapters -> epic
        val item500 = baseItem.copy(totalChapters = 500, currentChapter = "500")
        prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, emptyList(), listOf(item500), emptyMap(), 0L)
        )
        assertTrue(prog.milestones.find { it.id == "epic_series" }?.unlockedAtMs != null)
        
        // 500 chapters but not finished (progress = 50) -> not epic
        val itemUnfinished = baseItem.copy(totalChapters = 500, currentChapter = "500", progress = 50)
        prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, emptyList(), listOf(itemUnfinished), emptyMap(), 0L)
        )
        assertFalse(prog.milestones.find { it.id == "epic_series" }?.unlockedAtMs != null)
    }
    
    @Test
    fun `night_reader triggered at local hour 0-4 but not 5`() {
        val tz = TimeZone.getTimeZone("GMT")
        
        // 04:59:59 GMT
        val time459 = 4L * 3600_000L + 59 * 60_000L + 59 * 1000L
        val sessionAt4 = ReadingSessionEntity(
            novelKey = "1", startedAt = time459, endedAt = 0L, activeMillis = 0L, chaptersCompleted = 0
        )
        
        var prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, listOf(sessionAt4), emptyList(), emptyMap(), 0L, tz)
        )
        assertTrue(prog.milestones.find { it.id == "night_reader" }?.unlockedAtMs != null)
        
        // 05:00:00 GMT
        val time500 = 5L * 3600_000L
        val sessionAt5 = ReadingSessionEntity(
            novelKey = "1", startedAt = time500, endedAt = 0L, activeMillis = 0L, chaptersCompleted = 0
        )
        
        prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, listOf(sessionAt5), emptyList(), emptyMap(), 0L, tz)
        )
        assertFalse(prog.milestones.find { it.id == "night_reader" }?.unlockedAtMs != null)
    }
    
    @Test
    fun `seeded sessions do not trigger night_reader`() {
        val tz = TimeZone.getTimeZone("GMT")
        val sessionAt0 = ReadingSessionEntity(
            novelKey = "1", startedAt = 0L, endedAt = 0L, activeMillis = 0L, chaptersCompleted = 0, seeded = true
        )
        val prog = ScrollProgressionEngine.compute(
            ProgressionInput(0, 0, 0, 0, listOf(sessionAt0), emptyList(), emptyMap(), 0L, tz)
        )
        assertFalse(prog.milestones.find { it.id == "night_reader" }?.unlockedAtMs != null)
    }
}
