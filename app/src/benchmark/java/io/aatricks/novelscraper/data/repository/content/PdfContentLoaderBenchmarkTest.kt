package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.AreaBreakType
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PdfContentLoaderBenchmarkTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var opener: PdfDocumentOpener
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        context = mock()
        opener = DefaultPdfDocumentOpener(context)
        cacheDir = File(System.getProperty("java.io.tmpdir"), "pdf-bench-cache-${System.currentTimeMillis()}")
        cacheDir.mkdirs()
        whenever(context.cacheDir).thenReturn(cacheDir)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    @Test
    fun benchmarkPdfHandlePoolEnforcement() = runTest {
        val pdfFile = createPdf("Page 1", "Page 2", "Page 3", "Page 4", "Page 5")
        val activeCount = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val releaseGate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)
        val poolSize = 3

        val mockOpener = object : PdfDocumentOpener {
            override fun open(filePath: String): PdfDocumentHandle? {
                val count = callCount.incrementAndGet()
                
                val mockDoc = mock<com.itextpdf.kernel.pdf.PdfDocument>()
                whenever(mockDoc.isClosed).thenReturn(false)
                whenever(mockDoc.numberOfPages).thenReturn(5)
                
                val handle = spy(PdfDocumentHandle(mockDoc, null))
                
                doAnswer { invocation ->
                    activeCount.decrementAndGet()
                    invocation.callRealMethod()
                }.whenever(handle).close()

                if (count > 1) { // metadata check is call 1, subsequent are page loads
                    val current = activeCount.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, current) }
                    
                    // Block until we allow release
                    runBlocking { releaseGate.await() }
                }
                
                return handle
            }
        }

        val loader = PdfContentLoader(context, mockOpener)
        
        // Initial load to get the PdfLazyList
        val result = loader.loadPdfContent(pdfFile.absolutePath)
        assertTrue("Initial load should succeed, but got $result", result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements

        // Trigger more loads than poolSize
        repeat(poolSize + 2) { i ->
            list[i]
        }

        // Wait a bit for jobs to start and hit the mock opener
        delay(500)

        // Verify that only poolSize handles were ever active despite more requests
        val finalMaxActive = maxActive.get()
        assertTrue("Max active handles $finalMaxActive should be <= $poolSize", finalMaxActive <= poolSize)
        assertTrue("Should have reached at least some concurrency", finalMaxActive > 0)

        // Release the gate and wait for completion
        releaseGate.complete(Unit)
        delay(500)
        
        // Cleanup
        (list as java.io.Closeable).close()
        pdfFile.delete()
    }

    @Test
    fun benchmarkCloseIdleHandles() = runTest {
        val pdfFile = createPdf("Page 1")
        val createdHandles = mutableListOf<PdfDocumentHandle>()

        val mockOpener = object : PdfDocumentOpener {
            override fun open(filePath: String): PdfDocumentHandle? {
                val doc = mock<com.itextpdf.kernel.pdf.PdfDocument>()
                whenever(doc.isClosed).thenReturn(false)
                whenever(doc.numberOfPages).thenReturn(1)
                val handle = spy(PdfDocumentHandle(doc, null))
                createdHandles.add(handle)
                return handle
            }
        }

        val loader = PdfContentLoader(context, mockOpener)
        val result = loader.loadPdfContent(pdfFile.absolutePath)
        assertTrue("Initial load should succeed, but got $result", result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements

        // Load page 1 to put a handle in idle pool
        list[0]
        delay(200) 

        // Close the list
        (list as java.io.Closeable).close()

        // All created handles should be closed eventually
        assertTrue("At least 2 handles should have been created", createdHandles.size >= 2)
        createdHandles.forEach { 
            org.mockito.kotlin.verify(it).close()
        }
        pdfFile.delete()
    }

    private fun createPdf(vararg pages: String): File {
        val pdfFile = File.createTempFile("pdf-bench", ".pdf")
        val pdfDocument = PdfDocument(PdfWriter(pdfFile))
        val document = Document(pdfDocument)

        pages.forEachIndexed { index, text ->
            if (index > 0) {
                document.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            }
            document.add(Paragraph(text))
        }

        document.close()
        return pdfFile
    }
}
