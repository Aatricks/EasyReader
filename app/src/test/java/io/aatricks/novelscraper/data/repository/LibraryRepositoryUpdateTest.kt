package io.aatricks.novelscraper.data.repository

import io.aatricks.novelscraper.data.local.LibraryDao
import io.aatricks.novelscraper.data.local.PreferencesManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class LibraryRepositoryUpdateTest {

    @Mock
    private lateinit var libraryDao: LibraryDao

    @Mock
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(preferencesManager.loadLibraryItems()).thenReturn(emptyList())
        whenever(libraryDao.getAllItems()).thenReturn(flowOf(emptyList()))
        repository = LibraryRepository(libraryDao, preferencesManager)

        // Clear invocations from initialization
        clearInvocations(libraryDao)
    }

    @Test
    fun testUpdateNovelInfo_CallsDaoUpdate() = runBlocking {
        val itemId = "test-id"
        // Mock the updateNovelInfo call to return 1 (success)
        whenever(libraryDao.updateNovelInfo(any(), any(), any())).thenReturn(1)

        val result = repository.updateNovelInfo(itemId, "new-url", "new-source")

        assertTrue(result)

        // Verify that getAllItems was NOT called
        verify(libraryDao, never()).getAllItems()
        verify(libraryDao, never()).getItemById(any())
        verify(libraryDao, never()).insertItems(any())

        // Verify updateNovelInfo was called with correct params
        verify(libraryDao).updateNovelInfo(itemId, "new-url", "new-source")
        Unit
    }

    @Test
    fun testUpdateNovelInfo_ReturnsFalseOnFailure() = runBlocking {
        val itemId = "test-id"
        // Mock the updateNovelInfo call to return 0 (failure/no rows updated)
        whenever(libraryDao.updateNovelInfo(any(), any(), any())).thenReturn(0)

        val result = repository.updateNovelInfo(itemId, "new-url", "new-source")

        assertEquals(false, result)

        verify(libraryDao).updateNovelInfo(itemId, "new-url", "new-source")
        Unit
    }
}
