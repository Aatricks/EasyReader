package io.aatricks.easyreader.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesManagerTest {

    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        preferencesManager = PreferencesManager(context)
        preferencesManager.clearAll()
    }

    @Test
    fun `default appearance values are correct`() = runBlocking {
        assertEquals("SYSTEM", preferencesManager.themeMode)
        assertFalse(preferencesManager.dynamicColor)

        val snapshot = preferencesManager.appearanceSettings.first()
        assertEquals("SYSTEM", snapshot.themeMode)
        assertFalse(snapshot.dynamicColor)
    }

    @Test
    fun `theme mode persistence and snapshot emission`() = runBlocking {
        preferencesManager.themeMode = "DARK"
        assertEquals("DARK", preferencesManager.themeMode)

        val snapshot = preferencesManager.appearanceSettings.first()
        assertEquals("DARK", snapshot.themeMode)
    }

    @Test
    fun `dynamic color persistence and snapshot emission`() = runBlocking {
        preferencesManager.dynamicColor = true
        assertTrue(preferencesManager.dynamicColor)

        val snapshot = preferencesManager.appearanceSettings.first()
        assertTrue(snapshot.dynamicColor)
    }

    @Test
    fun `brightness defaults to full and persists in reader snapshot`() = runBlocking {
        assertEquals(1.0f, preferencesManager.brightness, 0.001f)
        assertEquals(1.0f, preferencesManager.readerSettings.first().brightness, 0.001f)

        preferencesManager.brightness = 0.45f

        assertEquals(0.45f, preferencesManager.brightness, 0.001f)
        assertEquals(0.45f, preferencesManager.readerSettings.first().brightness, 0.001f)
    }
}
