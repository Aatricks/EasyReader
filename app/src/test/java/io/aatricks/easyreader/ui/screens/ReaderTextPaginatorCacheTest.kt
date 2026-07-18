package io.aatricks.easyreader.ui.screens

import io.aatricks.easyreader.data.model.ContentElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTextPaginatorCacheTest {

    @Test
    fun `line boundary cache returns cached bounds on hit`() {
        val cache = ReaderTextLineCache(maxSize = 10)
        val key = TextMeasureKey(
            content = "Sample paragraph text",
            availableWidthPx = 800,
            fontSizeSp = 16f,
            lineHeightPx = 24f,
            fontFamily = "sans-serif"
        )

        var measureCount = 0
        val result1 = cache.getOrMeasure(key) {
            measureCount++
            listOf(6, 15, 21)
        }
        val result2 = cache.getOrMeasure(key) {
            measureCount++
            listOf(6, 15, 21)
        }

        assertEquals(1, measureCount)
        assertEquals(listOf(6, 15, 21), result1)
        assertEquals(listOf(6, 15, 21), result2)
    }

    @Test
    fun `different keys trigger new measurement in cache`() {
        val cache = ReaderTextLineCache(maxSize = 10)
        val key1 = TextMeasureKey("Text", 800, 16f, 24f, "sans-serif")
        val key2 = TextMeasureKey("Text", 400, 16f, 24f, "sans-serif")

        var count = 0
        cache.getOrMeasure(key1) { count++; listOf(4) }
        cache.getOrMeasure(key2) { count++; listOf(2, 4) }

        assertEquals(2, count)
    }

    @Test
    fun `dimension-only image updates keep pagination identity stable`() {
        val before = listOf(
            ContentElement.Text("Paragraph"),
            ContentElement.Image("http://example.com/page.jpg")
        )
        val after = listOf(
            ContentElement.Text("Paragraph"),
            ContentElement.Image("http://example.com/page.jpg", width = 800, height = 12_000)
        )

        assertEquals(
            paginationElementsWithoutImageDimensions(before),
            paginationElementsWithoutImageDimensions(after)
        )
    }

    @Test
    fun `scrolling mode performs zero text measurements`() = runTest {
        val paragraphs = List(1000) { index -> ContentElement.Text("Paragraph $index") }
        val isPagedMode = false

        val measureCounter = AtomicInteger(0)

        val pages = paginateReaderContentForMode(
            isPagedMode = isPagedMode,
            request = ReaderPaginationRequest(
                elements = paragraphs,
                pageHeightPx = 1000f,
                lineHeightPx = 20f,
                paragraphSpacingPx = 10f,
                lineEndsFor = {
                    measureCounter.incrementAndGet()
                    listOf(it.length)
                }
            )
        )

        assertEquals(0, pages.size)
        assertEquals(0, measureCounter.get())
    }

    @Test
    fun `scrolling mode does not build fallback reader pages`() {
        val paragraphs = List(1000) { index -> ContentElement.Text("Paragraph $index") }

        val pages = fallbackReaderPagesForMode(isPagedMode = false, elements = paragraphs)

        assertTrue(pages.isEmpty())
    }

    @Test
    fun `scrolling mode does not normalize pagination elements`() {
        val paragraphs = List(1000) { index -> ContentElement.Text("Paragraph $index") }

        val paginationElements = paginationElementsForMode(isPagedMode = false, elements = paragraphs)

        assertTrue(paginationElements.isEmpty())
    }

    @Test
    fun `cancellable pagination yields when budget exceeded`() = runTest {
        val paragraphs = List(200) { index -> ContentElement.Text("Paragraph text $index") }
        var nanoTime = 0L

        var yieldCount = 0
        val pages = paginateReaderContentCancellable(
            request = ReaderPaginationRequest(
                elements = paragraphs,
                pageHeightPx = 100f,
                lineHeightPx = 20f,
                paragraphSpacingPx = 5f,
                lineEndsFor = { listOf(it.length) }
            ),
            runtime = PaginationRuntime(
                timeProvider = {
                    nanoTime += 2_000_000L
                    nanoTime
                },
                yieldForFrame = { yieldCount++ }
            )
        )

        assertTrue(pages.isNotEmpty())
        assertTrue(yieldCount > 0)
    }

    @Test
    fun `cancellable pagination respects job cancellation`() = runTest {
        val paragraphs = List(500) { index -> ContentElement.Text("Paragraph text $index") }
        var nanoTime = 0L
        val yielded = CompletableDeferred<Unit>()

        val job = backgroundScope.launch {
            paginateReaderContentCancellable(
                request = ReaderPaginationRequest(
                    elements = paragraphs,
                    pageHeightPx = 100f,
                    lineHeightPx = 20f,
                    paragraphSpacingPx = 5f,
                    lineEndsFor = { listOf(it.length) }
                ),
                runtime = PaginationRuntime(
                    timeProvider = {
                        nanoTime += 10_000_000L
                        nanoTime
                    },
                    yieldForFrame = {
                        yielded.complete(Unit)
                        awaitCancellation()
                    }
                )
            )
        }
        testScheduler.runCurrent()
        assertTrue(yielded.isCompleted)
        job.cancel()
        testScheduler.runCurrent()
        assertTrue(job.isCancelled)
    }
}
