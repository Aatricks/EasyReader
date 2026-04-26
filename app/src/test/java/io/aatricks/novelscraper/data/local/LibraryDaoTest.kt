package io.aatricks.novelscraper.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aatricks.novelscraper.data.model.LibraryItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var libraryDao: LibraryDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        libraryDao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testGetItemByUrlDeterministic() = runBlocking {
        val url = "https://example.com/item"
        // Since version 4 has a unique index on URL, we can't have duplicates with insertItem.
        // But we can test that insertItem (REPLACE) works and returns the latest one.
        
        val item1 = LibraryItem(id = "1", title = "Item 1", url = url, lastRead = 1000)
        libraryDao.insertItem(item1)
        
        val item2 = LibraryItem(id = "2", title = "Item 2", url = url, lastRead = 2000)
        libraryDao.insertItem(item2)

        val retrieved = libraryDao.getItemByUrl(url)
        assertNotNull(retrieved)
        // With unique index on URL and REPLACE strategy, item1 should be replaced by item2
        assertEquals("2", retrieved?.id)
        assertEquals(2000L, retrieved?.lastRead)
    }

    @Test
    fun testIndicesExist() = runBlocking {
        // This is more of a smoke test that queries on indexed fields work
        val item = LibraryItem(
            id = "1", 
            title = "Test", 
            url = "https://example.com", 
            baseTitle = "Base",
            isCurrentlyReading = true,
            lastRead = 5000L
        )
        libraryDao.insertItem(item)
        
        val byUrl = libraryDao.getItemByUrl("https://example.com")
        assertNotNull(byUrl)
        
        val currentlyReading = libraryDao.getCurrentlyReading()
        assertNotNull(currentlyReading)
        assertEquals("1", currentlyReading?.id)
    }
}
