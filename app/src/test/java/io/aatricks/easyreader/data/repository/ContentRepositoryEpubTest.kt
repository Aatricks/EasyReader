package io.aatricks.easyreader.data.repository

import android.content.Context
import android.net.Uri
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.EpubBook
import io.aatricks.easyreader.data.repository.content.ContentUriTypeResolver
import io.aatricks.easyreader.data.repository.content.EpubContentLoader
import io.aatricks.easyreader.data.repository.content.LocalContentLoader
import io.aatricks.easyreader.data.repository.content.DefaultPdfDocumentOpener
import io.aatricks.easyreader.data.repository.content.PdfContentLoader
import io.aatricks.easyreader.data.repository.content.PdfDocumentOpener
import io.aatricks.easyreader.data.repository.content.WebContentLoader
import io.aatricks.easyreader.data.repository.content.ImageCache
import io.aatricks.easyreader.data.repository.content.ImageDownloader
import io.aatricks.easyreader.data.repository.content.ParsedContentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        tempDir = createTempDir("epub_large_test")
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
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val htmlDownloads = File(filesDir, "downloads/html").apply { mkdirs() }
        val mediaDownloads = File(filesDir, "downloads/media").apply { mkdirs() }
        val epubDownloads = File(filesDir, "downloads/epub").apply { mkdirs() }

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

        val webLoader = WebContentLoader(mockHtmlParser, okHttpClient, ImageCache(mediaCache, mediaDownloads), ImageDownloader(okHttpClient), ParsedContentCache(), htmlCache, htmlDownloads, io.aatricks.easyreader.data.repository.content.InMemoryPermanentFailureStore())
        val pdfLoader = PdfContentLoader(mockContext, DefaultPdfDocumentOpener(mockContext))
        val epubLoader = EpubContentLoader(mockContext, epubCache, epubDownloads)
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
                            <dc:title>Large Test Book</dc:title>
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
                        <docTitle><text>Large Test Book</text></docTitle>
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

    private fun createEpub3WithNavDocument(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>French Nav Test</dc:title>
                            <dc:creator>Test Author</dc:creator>
                        </metadata>
                        <manifest>
                            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                            <item id="chapter1" href="chapters/chapter1.xhtml" media-type="application/xhtml+xml"/>
                            <item id="chapter2" href="chapters/chapter2.xhtml" media-type="application/xhtml+xml"/>
                        </manifest>
                        <spine>
                            <itemref idref="nav" linear="no"/>
                            <itemref idref="chapter1"/>
                            <itemref idref="chapter2"/>
                        </spine>
                    </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                        <head><title>Sommaire</title></head>
                        <body>
                            <nav epub:type="toc">
                                <h1>Sommaire</h1>
                                <ol>
                                    <li><a href="chapters/chapter1.xhtml#start">Chapitre 1</a></li>
                                    <li><a href="chapters/chapter2.xhtml">Chapitre 2</a></li>
                                </ol>
                            </nav>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/chapters/chapter1.xhtml"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <head><title>Chapitre 1</title></head>
                        <body>
                            <h1>Chapitre 1</h1>
                            <p>This is real chapter one.</p>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/chapters/chapter2.xhtml"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <head><title>Chapitre 2</title></head>
                        <body>
                            <h1>Chapitre 2</h1>
                            <p>This is real chapter two.</p>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
    }

    private fun createCalibreSplitChapterEpub(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
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

            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uuid_id">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Split Chapter Test</dc:title>
                            <dc:creator>Kuji Furumiya</dc:creator>
                        </metadata>
                        <manifest>
                            <item id="titlepage" href="titlepage.xhtml" media-type="application/xhtml+xml"/>
                            <item id="index" href="index.html" media-type="application/xhtml+xml"/>
                            <item id="chapter1-image" href="chap1.html" media-type="application/xhtml+xml"/>
                            <item id="chapter1-text" href="chap_1.html" media-type="application/xhtml+xml"/>
                            <item id="chapter2-image" href="chap2.html" media-type="application/xhtml+xml"/>
                            <item id="chapter2-text" href="chap_2.html" media-type="application/xhtml+xml"/>
                            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                            <item id="chapter-image" href="embed0006_HD.jpg" media-type="image/jpeg"/>
                        </manifest>
                        <spine toc="ncx">
                            <itemref idref="titlepage"/>
                            <itemref idref="index"/>
                            <itemref idref="chapter1-image"/>
                            <itemref idref="chapter1-text"/>
                            <itemref idref="chapter2-image"/>
                            <itemref idref="chapter2-text"/>
                        </spine>
                    </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("toc.ncx"))
            zip.write(
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                        <docTitle><text>Split Chapter Test</text></docTitle>
                        <navMap>
                            <navPoint id="start" playOrder="1">
                                <navLabel><text>Start</text></navLabel>
                                <content src="titlepage.xhtml"/>
                                <navPoint id="chapter-one" playOrder="2">
                                    <navLabel><text>1. Chapter One</text></navLabel>
                                    <content src="chap1.html"/>
                                </navPoint>
                                <navPoint id="chapter-two" playOrder="3">
                                    <navLabel><text>2. Chapter Two</text></navLabel>
                                    <content src="chap2.html"/>
                                </navPoint>
                            </navPoint>
                        </navMap>
                    </ncx>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("titlepage.xhtml"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body><p>Cover</p></body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("index.html"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body>
                            <p>Contents</p>
                            <p><a href="chap1.html">1. Chapter One</a></p>
                            <p><a href="chap2.html">2. Chapter Two</a></p>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("chap1.html"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body>
                            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                                <image width="100" height="100" xlink:href="embed0006_HD.jpg" />
                            </svg>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("chap_1.html"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body>
                            <p>Chapter one body text.</p>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("chap2.html"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body><p>Chapter two title page.</p></body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("chap_2.html"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <body><p>Chapter two body text.</p></body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("embed0006_HD.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }

    private fun createFrontmatterHeavyTocEpub(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                        <rootfiles>
                            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                        </rootfiles>
                    </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Frontmatter Test</dc:title>
                            <dc:creator>Test Author</dc:creator>
                        </metadata>
                        <manifest>
                            <item id="toc-page" href="Text/section-0001.html" media-type="application/xhtml+xml"/>
                            <item id="color-gallery" href="Text/section-0002.html" media-type="application/xhtml+xml"/>
                            <item id="characters" href="Text/section-0004.html" media-type="application/xhtml+xml"/>
                            <item id="copyright" href="Text/section-0006.html" media-type="application/xhtml+xml"/>
                            <item id="title-page" href="Text/section-0007.html" media-type="application/xhtml+xml"/>
                            <item id="prologue" href="Text/section-0008.html" media-type="application/xhtml+xml"/>
                            <item id="prologue-extra" href="Text/section-0009.html" media-type="application/xhtml+xml"/>
                            <item id="chapter-one" href="Text/section-0010.html" media-type="application/xhtml+xml"/>
                            <item id="newsletter" href="Text/section-0011.html" media-type="application/xhtml+xml"/>
                            <item id="nav" href="Text/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        </manifest>
                        <spine toc="ncx">
                            <itemref idref="toc-page"/>
                            <itemref idref="color-gallery"/>
                            <itemref idref="characters"/>
                            <itemref idref="copyright"/>
                            <itemref idref="title-page"/>
                            <itemref idref="prologue"/>
                            <itemref idref="prologue-extra"/>
                            <itemref idref="chapter-one"/>
                            <itemref idref="newsletter"/>
                            <itemref idref="nav" linear="no"/>
                        </spine>
                    </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zip.write(
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                        <docTitle><text>Frontmatter Test</text></docTitle>
                        <navMap>
                            <navPoint id="toc" playOrder="1">
                                <navLabel><text>Table of Contents</text></navLabel>
                                <content src="Text/section-0001.html#tableofcontents"/>
                            </navPoint>
                            <navPoint id="gallery" playOrder="2">
                                <navLabel><text>Color Gallery</text></navLabel>
                                <content src="Text/section-0002.html"/>
                            </navPoint>
                            <navPoint id="characters" playOrder="3">
                                <navLabel><text>Characters</text></navLabel>
                                <content src="Text/section-0004.html"/>
                            </navPoint>
                            <navPoint id="copyright" playOrder="4">
                                <navLabel><text>Copyrights and Credits</text></navLabel>
                                <content src="Text/section-0006.html"/>
                            </navPoint>
                            <navPoint id="title-page" playOrder="5">
                                <navLabel><text>Title Page</text></navLabel>
                                <content src="Text/section-0007.html"/>
                            </navPoint>
                            <navPoint id="prologue" playOrder="6">
                                <navLabel><text>Prologue</text></navLabel>
                                <content src="Text/section-0008.html#auto_bookmark_toc_8"/>
                            </navPoint>
                            <navPoint id="chapter-one" playOrder="7">
                                <navLabel><text>Chapter 1: Journey to the Unknown Continent</text></navLabel>
                                <content src="Text/section-0010.html#auto_bookmark_toc_10"/>
                            </navPoint>
                            <navPoint id="newsletter" playOrder="8">
                                <navLabel><text>Newsletter</text></navLabel>
                                <content src="Text/section-0011.html"/>
                            </navPoint>
                        </navMap>
                    </ncx>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/Text/nav.xhtml"))
            zip.write(
                """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                        <body>
                            <nav epub:type="toc">
                                <ol>
                                    <li><a href="section-0001.html">Table of Contents</a></li>
                                    <li><a href="section-0008.html">Prologue</a></li>
                                    <li><a href="section-0010.html">Chapter 1</a></li>
                                </ol>
                            </nav>
                        </body>
                    </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            listOf(
                "section-0001.html" to "Table of Contents",
                "section-0002.html" to "Color Gallery",
                "section-0004.html" to "Characters",
                "section-0006.html" to "Copyrights and Credits",
                "section-0007.html" to "Title Page",
                "section-0008.html" to "Prologue body text.",
                "section-0009.html" to "Prologue continues here.",
                "section-0010.html" to "Chapter one body text.",
                "section-0011.html" to "Newsletter"
            ).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry("OEBPS/Text/$name"))
                zip.write(
                    """
                        <html xmlns="http://www.w3.org/1999/xhtml">
                            <body><p>$text</p></body>
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

    @Test
    fun testEpub3NavDocumentIsParsedAndSkippedAsDefaultContent() = runBlocking {
        val epub3File = File(tempDir, "epub3_nav.epub")
        createEpub3WithNavDocument(epub3File)

        val book = repository.getEpubBook(epub3File.absolutePath)

        assertNotNull(book)
        assertEquals(
            listOf("OEBPS/chapters/chapter1.xhtml", "OEBPS/chapters/chapter2.xhtml"),
            book!!.spine
        )
        assertEquals("Chapitre 1", book.toc.first().title)
        assertEquals("OEBPS/chapters/chapter1.xhtml", book.getFirstReadableHref())

        val result = repository.loadContent(epub3File.absolutePath)

        assertTrue("Expected EPUB load success but was $result", result is ContentResult.Success)
        val success = result as ContentResult.Success
        val text = success.elements
            .filterIsInstance<ContentElement.Text>()
            .joinToString("\n") { it.content }

        assertEquals("${epub3File.absolutePath}#OEBPS/chapters/chapter1.xhtml", success.url)
        assertEquals("Chapitre 1", success.title)
        assertTrue(text.contains("This is real chapter one."))
        assertFalse(text.contains("Sommaire"))
    }

    @Test
    fun testCalibreSplitChapterEpubLoadsChapterTextPastImageStub() = runBlocking {
        val splitFile = File(tempDir, "split_chapter.epub")
        createCalibreSplitChapterEpub(splitFile)

        val book = repository.getEpubBook(splitFile.absolutePath)

        assertNotNull(book)
        assertEquals(listOf("1. Chapter One", "2. Chapter Two"), book!!.toc.map { it.title })
        assertEquals("chap1.html", book.getFirstReadableHref())

        val chapter = repository.loadEpubChapterFull(splitFile.absolutePath, "chap1.html")

        assertNotNull(chapter)
        assertEquals("1. Chapter One", chapter!!.title)
        assertEquals("chap2.html", chapter.nextHref)
        assertEquals(null, chapter.previousHref)
        assertTrue(chapter.content.any { it is ContentElement.Image })
        assertTrue(chapter.getAllText().contains("Chapter one body text."))
        assertFalse(chapter.getAllText().contains("Contents"))

        val result = repository.loadContent(splitFile.absolutePath)

        assertTrue("Expected EPUB load success but was $result", result is ContentResult.Success)
        val success = result as ContentResult.Success
        val text = success.elements
            .filterIsInstance<ContentElement.Text>()
            .joinToString("\n") { it.content }

        assertEquals("${splitFile.absolutePath}#chap1.html", success.url)
        assertEquals("1. Chapter One", success.title)
        assertTrue(text.contains("Chapter one body text."))
        assertFalse(text.contains("Contents"))
    }

    @Test
    fun testFrontmatterHeavyTocStartsAtFirstNarrativeEntry() = runBlocking {
        val frontmatterFile = File(tempDir, "frontmatter.epub")
        createFrontmatterHeavyTocEpub(frontmatterFile)

        val book = repository.getEpubBook(frontmatterFile.absolutePath)

        assertNotNull(book)
        assertEquals("OEBPS/Text/section-0008.html", book!!.getFirstReadableHref())

        val chapter = repository.loadEpubChapterFull(frontmatterFile.absolutePath, book.getFirstReadableHref()!!)

        assertNotNull(chapter)
        assertEquals("Prologue", chapter!!.title)
        assertEquals(null, chapter.previousHref)
        assertEquals("OEBPS/Text/section-0010.html", chapter.nextHref)
        assertTrue(chapter.getAllText().contains("Prologue body text."))
        assertTrue(chapter.getAllText().contains("Prologue continues here."))
        assertFalse(chapter.getAllText().contains("Table of Contents"))

        val result = repository.loadContent(frontmatterFile.absolutePath)

        assertTrue("Expected EPUB load success but was $result", result is ContentResult.Success)
        val success = result as ContentResult.Success
        val text = success.elements
            .filterIsInstance<ContentElement.Text>()
            .joinToString("\n") { it.content }

        assertEquals("${frontmatterFile.absolutePath}#OEBPS/Text/section-0008.html", success.url)
        assertEquals("Prologue", success.title)
        assertTrue(text.contains("Prologue body text."))
        assertFalse(text.contains("Table of Contents"))
    }
}
