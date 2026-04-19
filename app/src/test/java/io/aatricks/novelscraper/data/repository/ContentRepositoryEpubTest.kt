package io.aatricks.novelscraper.data.repository

import android.content.Context
import android.net.Uri
import io.aatricks.novelscraper.data.model.EpubBook
import io.aatricks.novelscraper.data.repository.content.ContentUriTypeResolver
import io.aatricks.novelscraper.data.repository.content.EpubContentLoader
import io.aatricks.novelscraper.data.repository.content.LocalContentLoader
import io.aatricks.novelscraper.data.repository.content.PdfContentLoader
import io.aatricks.novelscraper.data.repository.content.WebContentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.ArgumentMatchers.isNull
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis

@ExperimentalCoroutinesApi
class ContentRepositoryEpubTest {

    private lateinit var tempDir: File
    private lateinit var epubFile: File
    private lateinit var repository: ContentRepository
    private val chapterCount = 100 // Reduced for CI speed
    private lateinit var mockedUriStatic: MockedStatic<Uri>

    @Before
    fun setup() {
        tempDir = createTempDir("epub_benchmark")
        epubFile = File(tempDir, "test_book.epub")
        createLargeEpub(epubFile, chapterCount)

        val mockContext = mock<Context>()
        val mockHtmlParser = mock<HtmlParser>()
        val okHttpClient = OkHttpClient()
        val mockContentResolver = mock<android.content.ContentResolver>()
        val contentUriTypeResolver = mock<ContentUriTypeResolver>()

        // Mock cache dirs
        val cacheDir = File(tempDir, "cache")
        cacheDir.mkdirs()
        // Ensure subdirectories exist as ContentRepository expects them
        val htmlCache = File(cacheDir, "html_cache").apply { mkdirs() }
        val mediaCache = File(cacheDir, "media_cache").apply { mkdirs() }
        val epubCache = File(cacheDir, "epub_cache").apply { mkdirs() }

        whenever(mockContext.cacheDir).thenReturn(cacheDir)
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)

        // Mock ContentResolver to return stream for our test file when requested
        val streamAnswer = { _: Any -> java.io.FileInputStream(epubFile) }
        whenever(mockContentResolver.openInputStream(any())).thenAnswer(streamAnswer)
        whenever(mockContentResolver.openInputStream(isNull())).thenAnswer(streamAnswer)
        whenever(mockContentResolver.getType(any())).thenReturn("application/epub+zip")
        whenever(contentUriTypeResolver.resolveMimeType(any())).thenReturn("application/epub+zip")

        // Mock Uri.parse
        mockedUriStatic = Mockito.mockStatic(Uri::class.java)
        val mockUri = mock<Uri>()
        whenever(mockUri.lastPathSegment).thenReturn("test_book.epub")
        // Use anyString() for Uri.parse
        mockedUriStatic.`when`<Uri> { Uri.parse(org.mockito.ArgumentMatchers.anyString()) }.thenReturn(mockUri)

        val webLoader = WebContentLoader(mockHtmlParser, okHttpClient, htmlCache, mediaCache)
        val pdfLoader = PdfContentLoader(mockContext)
        val epubLoader = EpubContentLoader(mockContext, epubCache)
        val localLoader = LocalContentLoader(mockContext, mockHtmlParser, pdfLoader, epubLoader, contentUriTypeResolver)

        repository = ContentRepository(webLoader, pdfLoader, epubLoader, localLoader, contentUriTypeResolver, mockContext, okHttpClient)
    }

    @After
    fun tearDown() {
        mockedUriStatic.close()
        tempDir.deleteRecursively()
    }

    private fun createLargeEpub(file: File, chapters: Int) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // Mimetype (must be first, uncompressed)
            val mimetypeEntry = ZipEntry("mimetype")
            mimetypeEntry.method = ZipEntry.STORED
            mimetypeEntry.size = "application/epub+zip".length.toLong()
            mimetypeEntry.compressedSize = "application/epub+zip".length.toLong()
            mimetypeEntry.crc = 0x2Cab616f // precalculated or ignore for test
            zip.putNextEntry(mimetypeEntry)
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // Container
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // OPF
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()

            for (i in 1..chapters) {
                manifestItems.append("<item id=\"ch$i\" href=\"chapter_$i.html\" media-type=\"application/xhtml+xml\"/>\n")
                spineItems.append("<itemref idref=\"ch$i\"/>\n")
            }

            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Benchmark Book</dc:title>
                            <dc:identifier id="bookid">urn:uuid:12345</dc:identifier>
                            <dc:language>en</dc:language>
                        </metadata>
                        <manifest>
                            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                            $manifestItems
                        </manifest>
                        <spine toc="ncx">
                            $spineItems
                        </spine>
                    </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // NCX (TOC)
            val navPoints = StringBuilder()
            for (i in 1..chapters) {
                navPoints.append(
                    """
                        <navPoint id="navPoint-$i" playOrder="$i">
                            <navLabel><text>Chapter $i</text></navLabel>
                            <content src="chapter_$i.html"/>
                        </navPoint>
                    """.trimIndent()
                )
            }

            zip.putNextEntry(ZipEntry("toc.ncx"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                        <head><meta name="dtb:uid" content="urn:uuid:12345"/></head>
                        <docTitle><text>Benchmark Book</text></docTitle>
                        <navMap>
                            $navPoints
                        </navMap>
                    </ncx>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // Chapters
            val padding = "x".repeat(1024) // 1KB of padding per chapter
            for (i in 1..chapters) {
                zip.putNextEntry(ZipEntry("chapter_$i.html"))
                zip.write(
                    """
                        <html>
                        <head><title>Chapter $i</title></head>
                        <body>
                            <h1>Chapter $i</h1>
                            <p>This is the content of chapter $i.</p>
                            <!-- $padding -->
                        </body>
                        </html>
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
            }
        }
    }

    @Test
    fun testLoadEpubChapterLocalFile() = runBlocking {
        // Test loading from local file path
        val lastChapterIndex = chapterCount
        val lastChapterHref = "chapter_$lastChapterIndex.html"

        val chapter = repository.loadEpubChapterFull(epubFile.absolutePath, lastChapterHref)
        assertNotNull(chapter)
        assertEquals("Chapter $lastChapterIndex", chapter!!.title)
    }

    @Test
    fun testLoadEpubChapterContentUri() = runBlocking {
        // Test loading from content:// URI
        // This triggers ensureLocalEpubFile caching logic
        val contentUri = "content://io.aatricks.novelscraper/test_book.epub"

        val lastChapterIndex = chapterCount
        val lastChapterHref = "chapter_$lastChapterIndex.html"

        val chapter = repository.loadEpubChapterFull(contentUri, lastChapterHref)
        assertNotNull("Failed to load chapter from content URI", chapter)
        assertEquals("Chapter $lastChapterIndex", chapter!!.title)

        // Verify cache file was created
        val cacheDir = File(tempDir, "cache/epub_cache")
        val cachedFiles = cacheDir.listFiles { _, name -> name.endsWith(".epub") }
        assertEquals(1, cachedFiles?.size ?: 0)
    }
}
