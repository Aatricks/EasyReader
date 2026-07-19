package io.aatricks.easyreader.data.backup

import android.content.Context
import android.net.Uri
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import io.aatricks.easyreader.data.repository.LibraryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class BackupV3Test {
    
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `settings backup v3 round trip and merge logic`() = runTest {
        val prefs = mock<PreferencesManager> {
            var finished = setOf("Series A")
            var milestones = mapOf("first_chapter" to 1000L, "chapters_100" to 2000L)
            var seeded = false
            on { scrollFinishedSeries } doAnswer { finished }
            on { scrollFinishedSeries = any() } doAnswer { finished = it.getArgument(0) }
            on { scrollUnlockedMilestones } doAnswer { milestones }
            on { scrollUnlockedMilestones = any() } doAnswer { milestones = it.getArgument(0) }
            on { scrollHistorySeeded } doAnswer { seeded }
            on { scrollHistorySeeded = any() } doAnswer { seeded = it.getArgument(0) }
        }
        
        val backupPayload = SettingsBackup(
            schemaVersion = 3,
            exportedAt = 123L,
            appVersionName = "1.0",
            reader = ReaderSettingsPayload(18f, 1.5f, "Default", 16, 0, 1f, "DARK", "MOSS", 1f),
            scrollFinishedSeries = listOf("Series B", "Series A"),
            scrollUnlockedMilestones = mapOf("first_chapter" to 1500L, "chapters_1000" to 3000L),
            scrollHistorySeeded = true
        )
        
        val jsonText = json.encodeToString(backupPayload)
        
        val context = mock<Context> {
            val cr = mock<android.content.ContentResolver> {
                on { openInputStream(any()) } doReturn ByteArrayInputStream(jsonText.toByteArray())
            }
            on { contentResolver } doReturn cr
        }
        
        val manager = SettingsBackupManager(context, prefs)
        val uri = mock<Uri>()
        manager.importFrom(uri)
        
        // Assert merges
        assertEquals(setOf("Series A", "Series B"), prefs.scrollFinishedSeries)
        // first_chapter was 1000, imported 1500 -> keeps 1000
        // chapters_100 was 2000, imported absent -> keeps 2000
        // chapters_1000 was absent, imported 3000 -> adds 3000
        val expectedMilestones = mapOf("first_chapter" to 1000L, "chapters_100" to 2000L, "chapters_1000" to 3000L)
        assertEquals(expectedMilestones, prefs.scrollUnlockedMilestones)
        assertTrue(prefs.scrollHistorySeeded)
    }

    @Test
    fun `legacy v2 settings backup still imports cleanly`() = runTest {
        val legacyJson = """
            {
              "schemaVersion": 2,
              "exportedAt": 1,
              "appVersionName": "1.0",
              "reader": {
                "fontSize": 18.0,
                "lineHeight": 1.5,
                "fontFamily": "Default",
                "margins": 16,
                "paragraphSpacing": 1.0,
                "readerTheme": "DARK",
                "accentTheme": "MOSS"
              }
            }
        """.trimIndent()
        
        val prefs = mock<PreferencesManager> {
            var finished = setOf("Series A")
            on { scrollFinishedSeries } doAnswer { finished }
            on { scrollFinishedSeries = any() } doAnswer { finished = it.getArgument(0) }
            on { scrollUnlockedMilestones } doReturn emptyMap()
            on { scrollUnlockedMilestones = any() } doAnswer { }
        }
        
        val context = mock<Context> {
            val cr = mock<android.content.ContentResolver> {
                on { openInputStream(any()) } doReturn ByteArrayInputStream(legacyJson.toByteArray())
            }
            on { contentResolver } doReturn cr
        }
        
        val manager = SettingsBackupManager(context, prefs)
        val uri = mock<Uri>()
        manager.importFrom(uri)
        
        // Defaults apply, so nothing new added to lists/maps
        assertEquals(setOf("Series A"), prefs.scrollFinishedSeries)
    }
}
