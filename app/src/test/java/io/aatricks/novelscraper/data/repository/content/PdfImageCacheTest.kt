package io.aatricks.novelscraper.data.repository.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.aatricks.novelscraper.data.model.ContentElement
import io.aatricks.novelscraper.data.model.ContentResult
import io.aatricks.novelscraper.util.CacheKeyUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class PdfImageCacheTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var opener: PdfDocumentOpener
    private lateinit var loader: PdfContentLoader
    
    private lateinit var mockedBitmapFactory: MockedStatic<BitmapFactory>
    private lateinit var mockedBitmap: MockedStatic<Bitmap>

    @Before
    fun setup() {
        context = mock()
        opener = DefaultPdfDocumentOpener(context)
        cacheDir = createTempDirectory("pdf-cache").toFile()
        whenever(context.cacheDir).thenReturn(cacheDir)
        loader = PdfContentLoader(context, opener)
        
        mockedBitmapFactory = mockStatic(BitmapFactory::class.java)
        mockedBitmap = mockStatic(Bitmap::class.java)
    }

    @After
    fun tearDown() {
        mockedBitmapFactory.close()
        mockedBitmap.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `pdf images are stored in document-scoped directory with deterministic names`() = runTest {
        val filePath = "/path/to/my.pdf"
        val pageNum = 1
        val docKey = CacheKeyUtils.keyFor(filePath)
        
        val mockBitmap = mock<Bitmap>()
        whenever(mockBitmap.compress(any(), anyInt(), any())).thenAnswer { invocation ->
            val out = invocation.getArgument<FileOutputStream>(2)
            out.write("fake-image-data".toByteArray())
            true
        }
        
        mockedBitmapFactory.`when`<Bitmap?> { 
            BitmapFactory.decodeByteArray(any(), anyInt(), anyInt(), any()) 
        }.thenReturn(mockBitmap)

        val extracted = PdfContentLoader.ExtractedResult(
            rawImages = listOf(
                PdfContentLoader.RawImage("bytes1".toByteArray(), 100, 200),
                PdfContentLoader.RawImage("bytes2".toByteArray(), 300, 400)
            ),
            rawText = "Some text"
        )

        val results = loader.processExtractedElements(filePath, pageNum, extracted)
        
        val images = results.filterIsInstance<ContentElement.Image>()
        assertEquals(2, images.size)
        
        val imagesDir = File(cacheDir, "pdf_images/$docKey")
        assertTrue(imagesDir.exists())
        
        val img0 = File(imagesDir, "page_1_image_0.webp")
        val img1 = File(imagesDir, "page_1_image_1.webp")
        
        assertTrue(img0.exists())
        assertTrue(img1.exists())
        assertEquals("file://${img0.absolutePath}", images[0].url)
        assertEquals("file://${img1.absolutePath}", images[1].url)
    }

    @Test
    fun `reprocessing same image reuses existing file`() = runTest {
        val filePath = "/path/to/my.pdf"
        val pageNum = 1
        val docKey = CacheKeyUtils.keyFor(filePath)
        
        val mockBitmap = mock<Bitmap>()
        whenever(mockBitmap.compress(any(), anyInt(), any())).thenAnswer { invocation ->
            val out = invocation.getArgument<FileOutputStream>(2)
            out.write("fake-image-data".toByteArray())
            true
        }
        
        mockedBitmapFactory.`when`<Bitmap?> { 
            BitmapFactory.decodeByteArray(any(), anyInt(), anyInt(), any()) 
        }.thenReturn(mockBitmap)

        val extracted = PdfContentLoader.ExtractedResult(
            rawImages = listOf(PdfContentLoader.RawImage("bytes1".toByteArray(), 100, 200)),
            rawText = ""
        )

        // First pass
        loader.processExtractedElements(filePath, pageNum, extracted)
        val imgFile = File(cacheDir, "pdf_images/$docKey/page_1_image_0.webp")
        
        // Explicitly set last modified to the past to ensure we can detect if it's overwritten
        val oldTime = System.currentTimeMillis() - 5000
        imgFile.setLastModified(oldTime)
        
        // Second pass
        loader.processExtractedElements(filePath, pageNum, extracted)
        
        assertEquals(oldTime, imgFile.lastModified())
    }

    @Test
    fun `clearCache removes only specific document images`() = runTest {
        val path1 = "/path/1.pdf"
        val path2 = "/path/2.pdf"
        val docKey1 = CacheKeyUtils.keyFor(path1)
        val docKey2 = CacheKeyUtils.keyFor(path2)
        
        val mockBitmap = mock<Bitmap>()
        whenever(mockBitmap.compress(any(), anyInt(), any())).thenAnswer { invocation ->
            val out = invocation.getArgument<FileOutputStream>(2)
            out.write("fake-image-data".toByteArray())
            true
        }
        mockedBitmapFactory.`when`<Bitmap?> { 
            BitmapFactory.decodeByteArray(any(), anyInt(), anyInt(), any()) 
        }.thenReturn(mockBitmap)

        loader.processExtractedElements(path1, 1, PdfContentLoader.ExtractedResult(
            listOf(PdfContentLoader.RawImage("b1".toByteArray(), 10, 10)), ""
        ))
        loader.processExtractedElements(path2, 1, PdfContentLoader.ExtractedResult(
            listOf(PdfContentLoader.RawImage("b2".toByteArray(), 10, 10)), ""
        ))
        
        assertTrue(File(cacheDir, "pdf_images/$docKey1").exists())
        assertTrue(File(cacheDir, "pdf_images/$docKey2").exists())
        
        loader.clearCache(path1)
        
        assertFalse(File(cacheDir, "pdf_images/$docKey1").exists())
        assertTrue(File(cacheDir, "pdf_images/$docKey2").exists())
    }

    @Test
    fun `clearAllCache removes all pdf images`() = runTest {
        val path1 = "/path/1.pdf"
        val path2 = "/path/2.pdf"
        
        val mockBitmap = mock<Bitmap>()
        whenever(mockBitmap.compress(any(), anyInt(), any())).thenAnswer { invocation ->
            val out = invocation.getArgument<FileOutputStream>(2)
            out.write("fake-image-data".toByteArray())
            true
        }
        mockedBitmapFactory.`when`<Bitmap?> { 
            BitmapFactory.decodeByteArray(any(), anyInt(), anyInt(), any()) 
        }.thenReturn(mockBitmap)

        loader.processExtractedElements(path1, 1, PdfContentLoader.ExtractedResult(
            listOf(PdfContentLoader.RawImage("b1".toByteArray(), 10, 10)), ""
        ))
        loader.processExtractedElements(path2, 1, PdfContentLoader.ExtractedResult(
            listOf(PdfContentLoader.RawImage("b2".toByteArray(), 10, 10)), ""
        ))
        
        assertTrue(File(cacheDir, "pdf_images").exists())
        
        loader.clearAllCache()
        
        assertFalse(File(cacheDir, "pdf_images").exists())
    }

    @Test
    fun `failed write deletes temp file and leaves no corrupt final file`() = runTest {
        val filePath = "/path/to/my.pdf"
        val pageNum = 1
        val docKey = CacheKeyUtils.keyFor(filePath)
        
        val mockBitmap = mock<Bitmap>()
        whenever(mockBitmap.compress(any(), anyInt(), any())).thenAnswer { 
            throw RuntimeException("Compression failed")
        }
        
        mockedBitmapFactory.`when`<Bitmap?> { 
            BitmapFactory.decodeByteArray(any(), anyInt(), anyInt(), any()) 
        }.thenReturn(mockBitmap)

        val extracted = PdfContentLoader.ExtractedResult(
            rawImages = listOf(PdfContentLoader.RawImage("bytes1".toByteArray(), 100, 200)),
            rawText = ""
        )

        loader.processExtractedElements(filePath, pageNum, extracted)
        
        val imagesDir = File(cacheDir, "pdf_images/$docKey")
        val finalFile = File(imagesDir, "page_1_image_0.webp")
        val tmpFile = File(imagesDir, "page_1_image_0.webp.tmp")
        
        assertFalse(finalFile.exists())
        assertFalse(tmpFile.exists())
    }
}
