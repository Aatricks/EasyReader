package io.aatricks.easyreader.data.repository.content

import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.HtmlParser
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class OfflineProgressTest {

    @Test
    fun progress_gate_cadence_and_terminal_flag_for_batch_sizes() = runTest {
        val testCases = listOf(20, 50, 100, 200)

        for (totalImages in testCases) {
            val emittedProgressList = mutableListOf<Pair<Int, Boolean>>()
            var fakeTimeMs = 1000L

            val gate = OfflineProgressGate(
                totalImages = totalImages,
                clock = { fakeTimeMs },
                onEmit = { count, isTerminal -> emittedProgressList.add(count to isTerminal) }
            )

            gate.emitInitial(0)

            for (i in 1..totalImages) {
                gate.onImageCompleted(i)
            }

            gate.emitTerminal(totalImages)

            assertEquals(0, emittedProgressList.first().first)
            assertFalse(emittedProgressList.first().second)

            assertEquals(totalImages, emittedProgressList.last().first)
            assertTrue(emittedProgressList.last().second)

            for (j in 1 until emittedProgressList.size - 1) {
                val diff = emittedProgressList[j].first - emittedProgressList[j - 1].first
                assertTrue("Emission step $diff should be >= 5", diff >= 5)
            }
        }
    }

    @Test
    fun store_downloadChapter_callback_cadence_and_linear_validations() = runTest {
        val testCases = listOf(20, 50, 100, 200)

        for (totalImages in testCases) {
            val tempDir = Files.createTempDirectory("offline-store-test-$totalImages").toFile()
            try {
                val htmlParser = mock<HtmlParser>()
                val imageDownloader = mock<ImageDownloader>()
                val imageCache = mock<ImageCache>()
                val failureStore = InMemoryPermanentFailureStore()

                val validationCount = AtomicInteger(0)
                val store = WebOfflineChapterStore(
                    rootDir = tempDir,
                    htmlParser = htmlParser,
                    imageDownloader = imageDownloader,
                    imageCache = imageCache,
                    permanentFailureStore = failureStore
                )
                store.imageValidator = { file ->
                    validationCount.incrementAndGet()
                    file.exists() && file.length() > 0
                }

                val imageUrls = (1..totalImages).map { "https://example.com/img-$it.jpg" }
                val elements = imageUrls.map { ContentElement.Image(it) }
                val url = "https://example.com/chapter-1"
                val document = Jsoup.parse("<html><body></body></html>")

                whenever(htmlParser.parse(any(), any())).thenReturn(elements)
                whenever(
                    imageDownloader.executeImageRequest(
                        any(),
                        any(),
                        any(),
                        anyOrNull(),
                        anyOrNull()
                    )
                ).thenAnswer { invocation ->
                    val dest = invocation.getArgument<File>(4)
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(byteArrayOf(1, 2, 3))
                    ImageFetchResult.Success(file = dest)
                }

                val progressEmissions = mutableListOf<PrefetchResult>()

                val result = store.downloadChapter(url, document) { progress ->
                    progressEmissions.add(progress)
                }

                assertTrue(result.isComplete)
                assertFalse(result.isInProgress)

                // Each completed image is validated once, by the download itself. The
                // terminal disk inspection adds none: it answers from the byte counts the
                // download just recorded in the manifest.
                assertEquals(
                    "Validation count must be exactly linear for $totalImages images",
                    totalImages,
                    validationCount.get()
                )

                // Verify callback cadence: step >= 5 for intermediates
                assertTrue("Must receive progress emissions", progressEmissions.size >= 2)
                assertEquals(0, progressEmissions.first().cachedImages)

                val terminalEmission = progressEmissions.last()
                assertEquals(totalImages, terminalEmission.cachedImages)
                assertFalse("Terminal emission must have isInProgress == false", terminalEmission.isInProgress)
                assertEquals("Exactly one terminal emission expected", 1, progressEmissions.count { !it.isInProgress })

                for (k in 1 until progressEmissions.size - 1) {
                    val step = progressEmissions[k].cachedImages - progressEmissions[k - 1].cachedImages
                    assertTrue("Intermediate step $step must be >= 5", step >= 5)
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
