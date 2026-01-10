package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
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
    fun testUpdateProgress() = runBlocking {
        val itemId = "test-id"
        val item = LibraryItem(id = itemId, title = "Test", url = "url")
        whenever(libraryDao.getItemById(itemId)).thenReturn(item)
        
        repository.updateProgress(itemId, "Chapter 2", 50)
        
        verify(libraryDao).insertItem(check {
            assertEquals("Chapter 2", it.currentChapter)
            assertEquals(50, it.progress)
        })
    }

    @Test
    fun testSelection() = runBlocking {
        val item1Id = "id1"
        val item2Id = "id2"
        
        repository.toggleSelection(item1Id)
        assertTrue(item1Id in repository.selectedItems.value)
        assertFalse(item2Id in repository.selectedItems.value)
        assertEquals(1, repository.selectedItems.value.size)
        
        repository.clearSelection()
        assertEquals(0, repository.selectedItems.value.size)
    }
}