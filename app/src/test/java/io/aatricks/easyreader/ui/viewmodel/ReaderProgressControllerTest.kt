package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.*
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.FieldUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            ReaderProgressState(
                scrollPosition = 50f,
                scrollProgress = 50,
                scrollIndex = 10,
                scrollElementKey = "txt:http://example.com/1:10:abc",
                scrollOffsetFraction = 0.5f,
                firstVisibleItemSize = 500
            )
        )
        // Mark the user as having interacted so the controller treats the live state as truth.
        controller.hasUserInteractedSinceLoad = true
        controller.suppressAutoNavUntilUserInteraction = false

        controller.saveCurrentProgress(content)

        verify(libraryRepository).updateProgressExplicit(
            itemId = eq("test-id"),
            currentChapter = eq(""),
            progress = eq(FieldUpdate.Set(50)),
            currentChapterUrl = eq(FieldUpdate.Set("http://example.com/1")),
            lastScrollProgress = eq(FieldUpdate.Set(50f)),
            lastReadIndex = eq(FieldUpdate.Set(10)),
            lastReadElementKey = eq(FieldUpdate.Set("txt:http://example.com/1:10:abc")),
            lastReadOffsetFraction = eq(FieldUpdate.Set(0.5f))
        )
    }

    @Test
    fun `calculateInitialPosition increments restoreRequestId`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        val content = ChapterContent(
            paragraphs = List(10) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )
        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/1",
            title = "Novel",
            progress = 40,
            lastScrollPosition = 40f
        )

        assertEquals(0L, controller.restoreRequestId)
        controller.calculateInitialPosition(content, libraryItem, fromBottom = false, isExplicitNavigation = false)
        assertEquals(1L, controller.restoreRequestId)
        // The explicit-nav path (which ignores the saved item) still counts as a genuine request.
        controller.calculateInitialPosition(content, libraryItem, fromBottom = false, isExplicitNavigation = true)
        assertEquals(2L, controller.restoreRequestId)
    }

    @Test
    fun `calculateInitialPosition restores via element key when present`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)

        val paragraphs = List(30) { ContentElement.Text("Text $it") }
        val targetIndex = 17
        val targetKey = stableContentElementKey("http://example.com/1", targetIndex, paragraphs[targetIndex])

        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 75,
            lastScrollPosition = 75f,
            // Wrong index — element key must win.
            lastReadIndex = 5,
            lastReadElementKey = targetKey,
            lastReadOffsetFraction = 0.5f,
            contentType = ContentType.WEB
        )

        val content = ChapterContent(
            paragraphs = paragraphs,
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )

        assertEquals(targetIndex, state.scrollIndex)
        assertEquals(targetKey, state.scrollElementKey)
        assertEquals(0.5f, state.scrollOffsetFraction)
        assertEquals(75f, state.scrollPosition)
    }

    @Test
    fun `calculateInitialPosition falls back to saved index when element key is empty`() = runTest {
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
            lastReadElementKey = "",
            lastReadOffsetFraction = 0.25f,
            contentType = ContentType.WEB
        )

        val content = ChapterContent(
            paragraphs = List(30) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )

        assertEquals(20, state.scrollIndex)
        assertEquals(0.25f, state.scrollOffsetFraction)
        // Resolver refreshes the element key from the resolved index for future saves.
        val expectedKey = stableContentElementKey("http://example.com/1", 20, content.paragraphs[20])
        assertEquals(expectedKey, state.scrollElementKey)
    }

    @Test
    fun `calculateInitialPosition derives index from percent for legacy rows`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)

        // No key, no offset, no fraction sentinel — pre-unification legacy row.
        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 50,
            lastScrollPosition = 50f,
            lastReadIndex = 0,
            lastReadElementKey = "",
            lastReadOffsetFraction = FRACTION_UNKNOWN,
            contentType = ContentType.WEB
        )

        val content = ChapterContent(
            paragraphs = List(101) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )

        // 50% of (101-1) = 50
        assertEquals(50, state.scrollIndex)
        assertEquals(0f, state.scrollOffsetFraction)
        assertEquals(50f, state.scrollPosition)
        assertTrue(state.scrollElementKey.isNotEmpty())
        assertEquals(50, controller.restoredProgressSnapshot?.scrollIndex)
    }

    @Test
    fun `calculateInitialPosition restores saved anchor even when progress is zero`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        val paragraphs = List(8) { ContentElement.Text("Text $it") }
        val targetIndex = 4
        val targetKey = stableContentElementKey("http://example.com/1", targetIndex, paragraphs[targetIndex])
        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 0,
            lastScrollPosition = 0f,
            lastReadIndex = targetIndex,
            lastReadElementKey = targetKey,
            lastReadOffsetFraction = 0.4f,
            contentType = ContentType.WEB
        )
        val content = ChapterContent(
            paragraphs = paragraphs,
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )

        assertEquals(targetIndex, state.scrollIndex)
        assertEquals(targetKey, state.scrollElementKey)
        assertEquals(0.4f, state.scrollOffsetFraction)
        assertEquals(true, state.isPreciseRestore)
    }

    @Test
    fun `calculateInitialPosition uses progress as percent source when lastScrollPosition is stale-zero`() = runTest {
        // Regression: divergent row where `progress` got persisted but `lastScrollPosition`
        // was left at 0 (e.g. a partial-field write). Before the fix this resolved to
        // derivedIndex=0 → reader at top while the seek bar showed 89%.
        val controller = ReaderProgressController(libraryRepository, this)

        val libraryItem = LibraryItem(
            id = "test-id",
            url = "http://example.com/novel",
            title = "Novel",
            currentChapter = "Chapter 1",
            currentChapterUrl = "http://example.com/1",
            progress = 89,
            lastScrollPosition = 0f,
            lastReadIndex = 0,
            lastReadElementKey = "",
            lastReadOffsetFraction = FRACTION_UNKNOWN,
            contentType = ContentType.WEB
        )

        val content = ChapterContent(
            paragraphs = List(101) { ContentElement.Text("Text $it") },
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = false
        )

        // 89% of (101-1) = 89
        assertEquals(89, state.scrollIndex)
        assertEquals(89f, state.scrollPosition)
        assertEquals(89, state.scrollProgress)
        assertTrue(state.scrollElementKey.isNotEmpty())
    }

    @Test
    fun `calculateInitialPosition for explicit navigation starts from top`() = runTest {
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

        val state = controller.calculateInitialPosition(
            content = content,
            libraryItem = libraryItem,
            fromBottom = false,
            isExplicitNavigation = true
        )

        assertEquals(0, state.scrollIndex)
        assertEquals(0f, state.scrollPosition)
        assertEquals(0f, state.scrollOffsetFraction)
        assertEquals("", state.scrollElementKey)
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

    @Test
    fun `updateScrollPosition skips persistence when item size is below stability threshold`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        controller.currentLibraryItemId = "test-id"
        val content = ChapterContent(
            paragraphs = listOf(
                ContentElement.Image("https://cdn.example.com/1.jpg"),
                ContentElement.Image("https://cdn.example.com/2.jpg"),
                ContentElement.Image("https://cdn.example.com/3.jpg")
            ),
            title = "Chapter 1",
            url = "https://example.com/webtoon/chapter-1"
        )

        controller.updateScrollPosition(
            scrollOffset = 1.4f,
            maxScrollOffset = 10f,
            viewportHeight = 1f,
            index = 1,
            offsetFraction = 0.3f,
            elementKey = "img:https://cdn.example.com/2.jpg",
            content = content,
            canScrollForward = true,
            firstVisibleItemSize = 48
        )
        advanceTimeBy(200)
        runCurrent()

        // Index updates immediately for UI tracking, but no DB write while size is unstable.
        assertEquals(1, controller.progressState.value.scrollIndex)
        assertEquals(FRACTION_UNKNOWN, controller.progressState.value.scrollOffsetFraction)
        verify(libraryRepository, never()).updateProgressExplicit(any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `isSnapshotPersistable rejects unstable and unknown fraction states`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        val content = ChapterContent(
            paragraphs = listOf(ContentElement.Text("Hello")),
            title = "Chapter 1",
            url = "http://example.com/1"
        )

        val unstable = ReaderProgressState(
            scrollPosition = 50f,
            scrollProgress = 50,
            scrollIndex = 0,
            scrollOffsetFraction = 0.5f,
            firstVisibleItemSize = 40 // below MIN_STABLE_ITEM_SIZE_PX
        )
        assertEquals(false, controller.isSnapshotPersistable(content, unstable))

        val unknown = ReaderProgressState(
            scrollPosition = 50f,
            scrollProgress = 50,
            scrollIndex = 0,
            scrollOffsetFraction = FRACTION_UNKNOWN,
            firstVisibleItemSize = 500
        )
        assertEquals(false, controller.isSnapshotPersistable(content, unknown))

        val stable = ReaderProgressState(
            scrollPosition = 50f,
            scrollProgress = 50,
            scrollIndex = 0,
            scrollOffsetFraction = 0.5f,
            firstVisibleItemSize = 500
        )
        assertEquals(true, controller.isSnapshotPersistable(content, stable))
    }

    @Test
    fun `isSnapshotPersistable accepts snapshot when current item is stable regardless of upstream image dimensions`() = runTest {
        // Earlier policy rejected the write if any image before the current position lacked
        // dimensions. That left the DB stuck at a stale percent on image-heavy chapters where
        // upstream dims trickle in slowly — see "lands higher than I was" bug.
        val controller = ReaderProgressController(libraryRepository, this)
        val unstableContent = ChapterContent(
            paragraphs = listOf(
                ContentElement.Image("https://cdn.example.com/panel-1.jpg"),
                ContentElement.Text("Text after image")
            ),
            title = "Chapter 1",
            url = "https://example.com/webtoon/chapter-1"
        )
        val snapshot = ReaderProgressState(
            scrollPosition = 20f,
            scrollProgress = 20,
            scrollIndex = 1,
            scrollElementKey = "txt:after-image",
            scrollOffsetFraction = 0.2f,
            firstVisibleItemSize = 500
        )

        assertEquals(true, controller.isSnapshotPersistable(unstableContent, snapshot))
    }

    @Test
    fun `isSnapshotPersistable allows paged position without image dimensions`() = runTest {
        val controller = ReaderProgressController(libraryRepository, this)
        val content = ChapterContent(
            paragraphs = listOf(ContentElement.Image("https://cdn.example.com/page-1.jpg")),
            title = "Chapter 1",
            url = "https://example.com/manga/chapter-1"
        )
        val snapshot = ReaderProgressState(
            scrollPosition = 0f,
            scrollProgress = 0,
            scrollIndex = 0,
            scrollElementKey = "img:https://cdn.example.com/page-1.jpg",
            scrollOffsetFraction = 0f,
            firstVisibleItemSize = ReaderProgressController.PAGED_POSITION_ITEM_SIZE_PX
        )

        assertEquals(true, controller.isSnapshotPersistable(content, snapshot))
    }
}
