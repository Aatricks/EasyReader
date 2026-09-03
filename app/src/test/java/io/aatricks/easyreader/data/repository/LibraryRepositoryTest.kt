package io.aatricks.easyreader.data.repository

import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.FRACTION_UNKNOWN
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class LibraryRepositoryTest {

    @Mock
    private lateinit var preferencesManager: PreferencesManager
    
    @Mock
    private lateinit var libraryDao: LibraryDao

    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(preferencesManager.loadLibraryItems()).thenReturn(emptyList())
        whenever(preferencesManager.loadCollapsedSources()).thenReturn(emptySet())
        whenever(libraryDao.getAllItems()).thenReturn(flowOf(emptyList()))
        repository = LibraryRepository(libraryDao, preferencesManager)
    }

    @Test
    fun testAddItem() = runBlocking {
        val item = repository.addItem("Test Book", "https://example.com", ContentType.WEB)
        
        assertEquals("Test Book", item.title)
        assertEquals("https://example.com", item.url)
        verify(libraryDao).insertItem(any())
    }

    @Test
    fun testRemoveItem() = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(id = itemId, title = "Test", url = "url")
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)
        
        val removed = repository.removeItem(itemId)
        
        assertTrue(removed)
        verify(libraryDao).deleteItem(item)
    }

    @Test
    fun testUpdateProgress(): Unit = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(id = itemId, title = "Test", url = "url")
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)
        
        repository.updateProgress(itemId, "Chapter 2", 50)

        // Targeted column update, never the REPLACE funnel.
        verify(libraryDao, never()).insertItem(any())
        verify(libraryDao).updateProgressFields(check {
            assertEquals("Chapter 2", it.currentChapter)
            assertEquals(50, it.progress)
        })
    }

    @Test
    fun testUpdateProgressExplicit(): Unit = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(
            id = itemId,
            title = "Test",
            url = "url",
            lastReadOffsetFraction = 0.5f,
            lastReadIndex = 10,
            lastReadElementKey = "img:https://cdn/x.jpg"
        )
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)

        repository.updateProgressExplicit(
            itemId = itemId,
            lastReadIndex = FieldUpdate.Set(20),
            lastReadElementKey = FieldUpdate.Set("img:https://cdn/new.jpg"),
            lastReadOffsetFraction = FieldUpdate.Set(FRACTION_UNKNOWN)
        )

        verify(libraryDao).updateProgressFields(check {
            assertEquals(itemId, it.id)
            assertEquals(20, it.lastReadIndex)
            assertEquals("img:https://cdn/new.jpg", it.lastReadElementKey)
            assertEquals(FRACTION_UNKNOWN, it.lastReadOffsetFraction)
        })
    }

    @Test
    fun testUpdateProgressExplicitPreserve(): Unit = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(
            id = itemId,
            title = "Test",
            url = "url",
            lastReadOffsetFraction = 0.5f,
            lastReadElementKey = "img:abc"
        )
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)

        // Passing null for opt-out fields should leave them unchanged.
        repository.updateProgress(itemId, "Chapter 1", 10, lastReadOffsetFraction = null, lastReadElementKey = null)

        verify(libraryDao).updateProgressFields(check {
            assertEquals(0.5f, it.lastReadOffsetFraction)
            assertEquals("img:abc", it.lastReadElementKey)
        })
    }

    @Test
    fun testResetProgressClearsAllFields() = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(
            id = itemId,
            title = "Test",
            url = "url",
            progress = 50,
            lastReadOffsetFraction = 0.5f,
            lastReadIndex = 10
        )
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)

        repository.resetProgress(itemId)

        verify(libraryDao).resetProgress(eq(itemId), any())
    }

    @Test
    fun testCollapsedSourcesDelegation() = runBlocking {
        doReturn(setOf("NovelFire")).whenever(preferencesManager).loadCollapsedSources()

        val loaded = repository.loadCollapsedSources()

        assertEquals(setOf("NovelFire"), loaded)
        repository.saveCollapsedSources(setOf("MangaBat"))
        verify(preferencesManager).saveCollapsedSources(setOf("MangaBat"))
    }

    @Test
    fun testMarkAsCurrentlyReadingClearsUpdates() = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(id = itemId, title = "Test", url = "url", baseTitle = "Test Base", hasUpdates = true)
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)

        repository.markAsCurrentlyReading(itemId)

        verify(libraryDao).clearUpdatesForBaseTitle("Test Base")
        verify(libraryDao).setCurrentReading(eq(itemId), any())
    }

    @Test
    fun testMarkAsCurrentlyReadingClearsUpdatesNoBaseTitle() = runBlocking {
        val itemId = "test-id-2"
        val item = LibraryItem(id = itemId, title = "Test", url = "url", baseTitle = "", hasUpdates = true)
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)

        repository.markAsCurrentlyReading(itemId)

        verify(libraryDao).clearUpdatesForId(itemId)
        verify(libraryDao).setCurrentReading(eq(itemId), any())
    }
}
