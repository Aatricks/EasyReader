package io.aatricks.easyreader.data.repository.content

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.AreaBreakType
import io.aatricks.easyreader.data.model.ContentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PdfContentLoaderConcurrencyTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        context = mock()
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
    fun testPdfHandlePoolEnforcement() = runTest {
        val pdfFile = createPdf("Page 1", "Page 2", "Page 3", "Page 4", "Page 5")
        val activeCount = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val releaseGate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)
        val poolSize = 3

        val mockOpener = object : PdfDocumentOpener {
            override fun open(filePath: String): PdfDocumentHandle? {
                val count = callCount.incrementAndGet()
                
                val mockDoc = mock<PdfDocument>()
                val mockPage = mock<com.itextpdf.kernel.pdf.PdfPage>()
                val mockRect = com.itextpdf.kernel.geom.Rectangle(0f, 0f, 600f, 800f)
                whenever(mockPage.pageSize).thenReturn(mockRect)
                whenever(mockDoc.isClosed).thenReturn(false)
                whenever(mockDoc.numberOfPages).thenReturn(5)
                whenever(mockDoc.getPage(anyInt())).thenReturn(mockPage)
                
                val handle = spy(PdfDocumentHandle(mockDoc, null))
                
                doAnswer { invocation ->
                    activeCount.decrementAndGet()
                    invocation.callRealMethod()
                }.whenever(handle).close()

                if (count > 1) {
                    val current = activeCount.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, current) }
                    runBlocking { releaseGate.await() }
                }
                
                return handle
            }
        }

        val loader = PdfContentLoader(context, mockOpener)
        
        val result = loader.loadPdfContent(pdfFile.absolutePath)
        assertTrue("Initial load should succeed, but got $result", result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements

        repeat(poolSize + 2) { i ->
            list[i]
        }

        Thread.sleep(200)

        val finalMaxActive = maxActive.get()
        assertTrue("Max active handles $finalMaxActive should be <= $poolSize", finalMaxActive <= poolSize)

        releaseGate.complete(Unit)
        Thread.sleep(200)
        
        (list as java.io.Closeable).close()
        pdfFile.delete()
    }

    @Test
    fun testCloseIdleHandles() = runTest {
        val pdfFile = createPdf("Page 1")
        val createdHandles = mutableListOf<PdfDocumentHandle>()

        val mockOpener = object : PdfDocumentOpener {
            override fun open(filePath: String): PdfDocumentHandle? {
                val doc = mock<PdfDocument>()
                val mockPage = mock<com.itextpdf.kernel.pdf.PdfPage>()
                val mockRect = com.itextpdf.kernel.geom.Rectangle(0f, 0f, 600f, 800f)
                whenever(mockPage.pageSize).thenReturn(mockRect)
                whenever(doc.isClosed).thenReturn(false)
                whenever(doc.numberOfPages).thenReturn(1)
                whenever(doc.getPage(anyInt())).thenReturn(mockPage)
                val handle = spy(PdfDocumentHandle(doc, null))
                createdHandles.add(handle)
                return handle
            }
        }

        val loader = PdfContentLoader(context, mockOpener)
        val result = loader.loadPdfContent(pdfFile.absolutePath)
        assertTrue("Initial load should succeed, but got $result", result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements

        list[0]
        Thread.sleep(200)

        (list as java.io.Closeable).close()

        assertTrue("At least 1 handle should have been created", createdHandles.isNotEmpty())
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
