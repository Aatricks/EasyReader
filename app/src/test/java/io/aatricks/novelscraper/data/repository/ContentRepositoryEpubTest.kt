package io.aatricks.novelscraper.data.repository

import android.content.Context
import io.aatricks.novelscraper.data.model.EpubBook
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest

class ContentRepositoryEpubTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var contentRepository: ContentRepository
    private lateinit var mockContext: Context
    private lateinit var mockHtmlParser: HtmlParser
    private lateinit var mockOkHttpClient: OkHttpClient

    @Before
    fun setup() {
        mockContext = mock()
        mockHtmlParser = mock()
        mockOkHttpClient = mock()

        whenever(mockContext.cacheDir).thenReturn(tempFolder.root)

        contentRepository = ContentRepository(mockContext, mockHtmlParser, mockOkHttpClient)
    }

    @Test
    fun testParseEpubFile() = runTest {
        val epubFile = createDummyEpub()

        val book = contentRepository.getEpubBook(epubFile.absolutePath)

        assertEquals("Test Book Title", book?.metadata?.title)
        assertEquals("Test Author", book?.metadata?.author)
        assertEquals(2, book?.spine?.size) // chapter1.html, chapter2.html
        assertEquals(2, book?.toc?.size) // Chapter 1, Chapter 2
    }

    private fun createDummyEpub(): File {
        val file = tempFolder.newFile("test_book.epub")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // mimetype
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // META-INF/container.xml
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // OEBPS/content.opf
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Test Book Title</dc:title>
                        <dc:creator>Test Author</dc:creator>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="ch1" href="chapter1.html" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="chapter2.html" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                    </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // OEBPS/toc.ncx
            zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <navMap>
                        <navPoint id="navPoint-1" playOrder="1">
                            <navLabel><text>Chapter 1</text></navLabel>
                            <content src="chapter1.html"/>
                        </navPoint>
                        <navPoint id="navPoint-2" playOrder="2">
                            <navLabel><text>Chapter 2</text></navLabel>
                            <content src="chapter2.html"/>
                        </navPoint>
                    </navMap>
                </ncx>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // OEBPS/chapter1.html
            zip.putNextEntry(ZipEntry("OEBPS/chapter1.html"))
            zip.write("<html><body><h1>Chapter 1</h1><p>Content 1</p></body></html>".toByteArray())
            zip.closeEntry()

            // OEBPS/chapter2.html
            zip.putNextEntry(ZipEntry("OEBPS/chapter2.html"))
            zip.write("<html><body><h1>Chapter 2</h1><p>Content 2</p></body></html>".toByteArray())
            zip.closeEntry()
        }
        return file
    }
}
