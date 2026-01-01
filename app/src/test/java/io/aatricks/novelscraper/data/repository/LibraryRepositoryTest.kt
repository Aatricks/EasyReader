package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.LibraryItem
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

class LibraryRepositoryTest {

    @Mock
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(preferencesManager.loadLibraryItems()).thenReturn(emptyList())
        repository = LibraryRepository(preferencesManager)
    }

    @Test
    fun testAddItem() = runBlocking {
        val item = repository.addItem("Test Book", "https://example.com", ContentType.WEB)
        
        assertEquals("Test Book", item.title)
        assertEquals("https://example.com", item.url)
        assertEquals(1, repository.libraryItems.value.size)
        verify(preferencesManager).saveLibraryItems(anyList())
    }

    @Test
    fun testRemoveItem() = runBlocking {
        val item = repository.addItem("Test Book", "https://example.com", ContentType.WEB)
        val removed = repository.removeItem(item.id)
        
        assertTrue(removed)
        assertEquals(0, repository.libraryItems.value.size)
    }

    @Test
    fun testUpdateProgress() = runBlocking {
        val item = repository.addItem("Test Book", "https://example.com", ContentType.WEB)
        repository.updateProgress(item.id, "Chapter 2", 50)
        
        val updatedItem = repository.getItemById(item.id)
        assertEquals("Chapter 2", updatedItem?.currentChapter)
        assertEquals(50, updatedItem?.progress)
    }

    @Test
    fun testGrouping() = runBlocking {
        repository.addItem("Book 1 - Ch 1", "url1", ContentType.WEB, baseTitle = "Book 1")
        repository.addItem("Book 1 - Ch 2", "url2", ContentType.WEB, baseTitle = "Book 1")
        repository.addItem("Book 2 - Ch 1", "url3", ContentType.WEB, baseTitle = "Book 2")
        
        val grouped = repository.getGroupedByTitle()
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["Book 1"]?.size)
        assertEquals(1, grouped["Book 2"]?.size)
    }

    @Test
    fun testSelection() = runBlocking {
        val item1 = repository.addItem("Book 1", "url1", ContentType.WEB)
        val item2 = repository.addItem("Book 2", "url2", ContentType.WEB)
        
        repository.toggleSelection(item1.id)
        assertTrue(repository.isSelected(item1.id))
        assertFalse(repository.isSelected(item2.id))
        assertEquals(1, repository.getSelectionCount())
        
        repository.selectAll()
        assertEquals(2, repository.getSelectionCount())
        
        repository.clearSelection()
        assertEquals(0, repository.getSelectionCount())
    }
}
