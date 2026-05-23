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
        // Window skips current index and checks 1..6.
        prefetchImages(
            currentIndex = 0,
            content = content,
            requestedIndices = requestedIndices,
            onEnqueue = onEnqueue
        )

        // Assert 1
        // Expected requests: url1 (idx 1), url3 (idx 3), url4 (idx 4), url6a (idx 6), url6b (idx 6)
        assertEquals(5, enqueuedUrls.size)
        assertTrue(enqueuedUrls.containsAll(listOf("url1", "url3", "url4", "url6a", "url6b")))
        assertTrue(requestedIndices.containsAll(listOf(1, 2, 3, 4, 5, 6)))

        // Clear enqueued to verify new ones
        enqueuedUrls.clear()

        // Act 2: Scroll slightly (index 1)
        // Window: 0..7, skipping current index.
        // Range 0..6 is already requested.
        // If prefetchImages is optimized, it should NOT request anything for 0..6.
        // It might check 7, 8... but they don't exist.
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

        // Scroll to 0. Range skips current and checks 1..6.
        prefetchImages(0, largeContent, largeRequested, { largeEnqueued.add(it) })
        // Images at 1, 3, 5. (3 images)
        assertEquals(3, largeEnqueued.size)
        assertTrue(largeRequested.contains(1))

        largeEnqueued.clear()

        // Scroll to 6. Range: 4..12, skipping current.
        // New image indices are 7, 9, and 11.
        prefetchImages(6, largeContent, largeRequested, { largeEnqueued.add(it) })

        assertEquals(3, largeEnqueued.size)
        assertTrue(largeEnqueued.containsAll(listOf("url7", "url9", "url11")))
        assertTrue(largeRequested.contains(11))
    }
}
