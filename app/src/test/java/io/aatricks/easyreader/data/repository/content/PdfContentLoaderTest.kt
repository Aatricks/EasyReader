package io.aatricks.easyreader.data.repository.content

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.AreaBreakType
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class PdfContentLoaderTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var opener: PdfDocumentOpener
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        context = mock()
        opener = DefaultPdfDocumentOpener(context)
        cacheDir = createTempDirectory(prefix = "pdf-loader-cache").toFile()
        whenever(context.cacheDir).thenReturn(cacheDir)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `loadPdfContent preloads requested resume page`() = runTest {
        val pdfFile = createPdf("First page", "Second page")
        val loader = PdfContentLoader(context, opener)

        val result = loader.loadPdfContent(pdfFile.absolutePath, preloadPageIndex = 1)

        assertTrue(result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements
        val loadedPage = list[1]
        assertTrue(loadedPage is ContentElement.PageContent)
        val pageContent = loadedPage as ContentElement.PageContent
        assertTrue(pageContent.elements.filterIsInstance<ContentElement.Text>().any { it.content.contains("Second page") })

        (list as java.io.Closeable).close()
        pdfFile.delete()
    }

    @Test
    fun `loadPdfContent clamps resume page to document bounds`() = runTest {
        val pdfFile = createPdf("First page", "Second page")
        val loader = PdfContentLoader(context, opener)

        val result = loader.loadPdfContent(pdfFile.absolutePath, preloadPageIndex = 99)

        assertTrue(result is ContentResult.Success)
        val list = (result as ContentResult.Success).elements
        val loadedPage = list[1]
        assertTrue(loadedPage is ContentElement.PageContent)

        (list as java.io.Closeable).close()
        pdfFile.delete()
    }

    @Test
    fun `loading profile is tuned for smooth scrolling`() {
        val loader = PdfContentLoader(context, opener)
        val profile = loader.loadingProfileForTests()

        assertEquals(3, profile.prefetchForward)
        assertEquals(0, profile.prefetchBackward)
        assertEquals(6, profile.maxInFlightJobs)
    }

    private fun createPdf(vararg pages: String): File {
        val pdfFile = File.createTempFile("pdf-loader", ".pdf")
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
