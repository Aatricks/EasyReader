package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderProgressControllerTest {

    private val libraryRepository: LibraryRepository = mock()

    @Test
    fun `saveCurrentProgress sends correct values to repository`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        controller.currentLibraryItemId = "test-id"
        
        val content = ChapterContent(
            paragraphs = listOf(ContentElement.Text("Hello")),
            title = "Chapter 1",
            url = "http://example.com/1"
        )
        
        controller.syncProgressState(
            scrollPosition = 50f,
            scrollProgress = 50,
            scrollIndex = 10,
            scrollOffset = 100,
            scrollOffsetFraction = 0.5f
        )
        
        controller.saveCurrentProgress(content)
        
        verify(libraryRepository).updateProgressExplicit(
            itemId = eq("test-id"),
            currentChapter = eq(""),
            progress = eq(FieldUpdate.Set(50)),
            currentChapterUrl = eq(FieldUpdate.Set("http://example.com/1")),
            lastScrollProgress = eq(FieldUpdate.Set(50f)),
            lastReadIndex = eq(FieldUpdate.Set(10)),
            lastReadOffset = eq(FieldUpdate.Set(100)),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.5f))
        )
    }

    @Test
    fun `calculateInitialScroll restores from library item`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        
        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 75,
            lastScrollPosition = 75f,
            lastReadIndex = 20,
            lastReadOffset = 200,
            lastReadOffsetFraction = 0.75f,
            contentType = ContentType.WEB
        )
        
        val content = ChapterContent(
            paragraphs = List(30) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )
        
        val scrollState = controller.calculateInitialScroll(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )
        
        assertEquals(20, scrollState.index)
        assertEquals(75f, scrollState.position)
        assertEquals(75, scrollState.progress)
        assertEquals(0, scrollState.offset) // offset is 0 because offsetFraction is present
        assertEquals(0.75f, scrollState.offsetFraction)
        assertEquals(75f, scrollState.targetPosition)
    }

    @Test
    fun `calculateInitialScroll for explicit navigation starts from top`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        
        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 75,
            contentType = ContentType.WEB
        )
        
        val content = ChapterContent(
            paragraphs = List(30) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )
        
        val scrollState = controller.calculateInitialScroll(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = true
        )
        
        assertEquals(0, scrollState.index)
        assertEquals(0f, scrollState.position)
        assertEquals(0, scrollState.progress)
        assertEquals(0, scrollState.offset)
        assertEquals(0f, scrollState.offsetFraction)
    }

    @Test
    fun `onUserInteraction clears restoration flags`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        controller.suppressAutoNavUntilUserInteraction = true
        controller.hasUserInteractedSinceLoad = false
        controller.restoredProgressSnapshot = ReaderProgressState(scrollPosition = 50f)
        
        controller.onUserInteraction(
            uiTargetScrollPosition = 50f,
            uiPendingRestoreOffsetFraction = 0.5f,
            updateUiState = { target, offset ->
                assertNull(target)
                assertNull(offset)
            }
        )
        
        assertEquals(true, controller.hasUserInteractedSinceLoad)
        assertEquals(false, controller.suppressAutoNavUntilUserInteraction)
        assertNull(controller.restoredProgressSnapshot)
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
