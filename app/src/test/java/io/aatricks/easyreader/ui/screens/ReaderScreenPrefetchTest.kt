package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreenPrefetchTest {

    @Test
    fun `prefetchImages enqueues requests only for new indices`() {
        // Setup
        val paragraphs = listOf(
            ContentElement.Text("p0"),
            ContentElement.Image("url1"), // index 1
            ContentElement.Text("p2"),
            ContentElement.Image("url3"), // index 3
            ContentElement.Image("url4"), // index 4
            ContentElement.Text("p5"),
            ContentElement.ImageGroup(listOf(ContentElement.Image("url6a"), ContentElement.Image("url6b"))) // index 6
        )
        val content = ChapterContent(
            paragraphs = paragraphs,
            url = "http://example.com/chapter1"
        )
        val requestedIndices = mutableSetOf<Int>()
        val enqueuedUrls = mutableListOf<String>()
        val onEnqueue: (String) -> Unit = { url -> enqueuedUrls.add(url) }

        // Act 1: Initial prefetch (index 0)
        // Window is ahead=4 / behind=1, so it skips current index and checks 0..4.
        prefetchImages(
            currentIndex = 0,
            content = content,
            requestedIndices = requestedIndices,
            onEnqueue = onEnqueue
        )

        // Assert 1
        // Expected requests: url1 (idx 1), url3 (idx 3), url4 (idx 4). Idx 5/6 are outside the window.
        assertEquals(3, enqueuedUrls.size)
        assertTrue(enqueuedUrls.containsAll(listOf("url1", "url3", "url4")))
        assertTrue(requestedIndices.containsAll(listOf(1, 2, 3, 4)))

        // Clear enqueued to verify new ones
        enqueuedUrls.clear()

        // Act 2: Scroll slightly (index 1)
        // Window: 0..5, skipping current index. Idx 2/3/4 are already requested; idx 0/5 are Text,
        // so nothing new is enqueued.
        prefetchImages(
            currentIndex = 1,
            content = content,
            requestedIndices = requestedIndices,
            onEnqueue = onEnqueue
        )

        // Assert 2
        assertEquals(0, enqueuedUrls.size)

        // Act 3: Add new content/simulate scrolling to new area if we had more content
        // Let's create a larger content list to test valid scrolling
        val largeParagraphs = (0..20).map { i ->
            if (i % 2 == 0) ContentElement.Text("p$i") else ContentElement.Image("url$i")
        }
        val largeContent = ChapterContent(paragraphs = largeParagraphs, url = "http://test.com")
        val largeRequested = mutableSetOf<Int>()
        val largeEnqueued = mutableListOf<String>()

        // Scroll to 0. Range skips current and checks 0..4.
        prefetchImages(0, largeContent, largeRequested, { largeEnqueued.add(it) })
        // Images at odd indices 1, 3. (2 images)
        assertEquals(2, largeEnqueued.size)
        assertTrue(largeRequested.contains(1))

        largeEnqueued.clear()

        // Scroll to 6. Range: 5..10, skipping current.
        // New image indices are 5, 7, and 9.
        prefetchImages(6, largeContent, largeRequested, { largeEnqueued.add(it) })

        assertEquals(3, largeEnqueued.size)
        assertTrue(largeEnqueued.containsAll(listOf("url5", "url7", "url9")))
        assertTrue(largeRequested.contains(9))
    }
}
