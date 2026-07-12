package io.aatricks.easyreader.data.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy settings backup defaults brightness to full`() {
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

        val decoded = json.decodeFromString<SettingsBackup>(legacyJson)

        assertEquals(1.0f, decoded.reader.brightness, 0.001f)
    }

    @Test
    fun `settings backup round trips brightness`() {
        val backup = SettingsBackup(
            exportedAt = 1L,
            appVersionName = "1.0",
            reader = ReaderSettingsPayload(
                fontSize = 18f,
                lineHeight = 1.5f,
                fontFamily = "Default",
                margins = 16,
                paragraphSpacing = 1f,
                readerTheme = "DARK",
                accentTheme = "MOSS",
                brightness = 0.45f
            )
        )

        val decoded = json.decodeFromString<SettingsBackup>(json.encodeToString(backup))

        assertEquals(0.45f, decoded.reader.brightness, 0.001f)
    }
}
