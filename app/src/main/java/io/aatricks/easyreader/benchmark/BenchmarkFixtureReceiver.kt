package io.aatricks.easyreader.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.di.WebOfflineDownloadsDir
import io.aatricks.easyreader.util.CacheKeyUtils
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@AndroidEntryPoint
class BenchmarkFixtureReceiver : BroadcastReceiver() {

    @Inject lateinit var libraryDao: LibraryDao
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject @WebOfflineDownloadsDir lateinit var offlineRoot: File

    override fun onReceive(context: Context, intent: Intent) {
        val fixture = BenchmarkFixture.fromWireName(intent.getStringExtra(EXTRA_FIXTURE))
        if (intent.action != ACTION_SEED || fixture == null) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("ERROR:unsupported fixture")
            return
        }

        val result = runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    seed(context.applicationContext, fixture)
                }
            }
        }
        result.onSuccess {
            setResultCode(Activity.RESULT_OK)
            setResultData("READY:${fixture.wireName}")
        }.onFailure { throwable ->
            Log.e(TAG, "Fixture seed failed for ${fixture.wireName}", throwable)
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("ERROR:${throwable.message}")
        }
    }

    private suspend fun seed(context: Context, fixture: BenchmarkFixture) {
        resetFixtureState(context)
        when (fixture) {
            BenchmarkFixture.LIBRARY_500 -> seedLibrary()
            BenchmarkFixture.TEXT_500_PAGED -> seedText(TEXT_500_COUNT, ReadingMode.PAGED)
            BenchmarkFixture.TEXT_1000_PAGED -> seedText(TEXT_1000_COUNT, ReadingMode.PAGED)
            BenchmarkFixture.TEXT_1000_SCROLL -> seedText(TEXT_1000_COUNT, ReadingMode.VERTICAL)
            BenchmarkFixture.MANHWA_TALL -> seedManhwa()
            BenchmarkFixture.PDF -> seedPdf(context)
        }
    }

    private suspend fun resetFixtureState(context: Context) {
        libraryDao.deleteAllItems()
        preferencesManager.clearAll()
        offlineRoot.deleteRecursively()
        check(offlineRoot.mkdirs() || offlineRoot.isDirectory)
        File(context.filesDir, FIXTURE_DIR).deleteRecursively()
        preferencesManager.webOfflinePipelineVersion = WEB_OFFLINE_PIPELINE_VERSION
    }

    private suspend fun seedLibrary() {
        val now = System.currentTimeMillis()
        val items = (1..LIBRARY_CHAPTER_COUNT).map { chapter ->
            LibraryItem(
                id = "benchmark-library-$chapter",
                title = "$SERIES_TITLE - Chapter $chapter",
                url = "$LIBRARY_BASE_URL/chapter-$chapter",
                timestamp = now,
                currentChapter = "Chapter $chapter",
                totalChapters = LIBRARY_CHAPTER_COUNT,
                dateAdded = now,
                lastRead = now - chapter,
                baseTitle = SERIES_TITLE,
                baseNovelUrl = LIBRARY_BASE_URL,
                sourceName = SOURCE_NAME
            )
        }
        libraryDao.insertItems(items)
    }

    private suspend fun seedText(paragraphCount: Int, readingMode: ReadingMode) {
        val modeName = readingMode.name.lowercase()
        val chapterUrl = "$FIXTURE_BASE_URL/text-$paragraphCount-$modeName"
        val elements = (1..paragraphCount).map { paragraph ->
            ContentElement.Text(
                "Benchmark paragraph $paragraph. " +
                    "This deterministic sentence exercises reader layout, pagination, and restore behavior."
            )
        }
        writeManifest(chapterUrl, "Benchmark text $paragraphCount", elements, emptyList())
        restoreCurrentItem(
            url = chapterUrl,
            title = "Benchmark text $paragraphCount",
            contentType = ContentType.WEB,
            readingMode = readingMode
        )
    }

    private suspend fun seedManhwa() {
        val chapterUrl = "$FIXTURE_BASE_URL/manhwa-tall"
        val imageUrl = "$FIXTURE_BASE_URL/tall-strip.jpg"
        val fileName = "tall-strip.jpg"
        val chapterDir = File(offlineRoot, CacheKeyUtils.keyFor(chapterUrl))
        val imageDir = File(chapterDir, IMAGE_DIR)
        check(imageDir.mkdirs() || imageDir.isDirectory)
        val imageFile = File(imageDir, fileName)
        writeTallImage(imageFile)
        val image = ContentElement.Image(
            url = imageUrl,
            altText = "Benchmark tall image",
            width = TALL_IMAGE_WIDTH,
            height = TALL_IMAGE_HEIGHT
        )
        writeManifest(
            chapterUrl = chapterUrl,
            title = "Benchmark tall manhwa",
            elements = listOf(image),
            images = listOf(
                FixtureImageRecord(
                    url = imageUrl,
                    fileName = fileName,
                    width = TALL_IMAGE_WIDTH,
                    height = TALL_IMAGE_HEIGHT,
                    bytes = imageFile.length()
                )
            )
        )
        restoreCurrentItem(
            url = chapterUrl,
            title = "Benchmark tall manhwa",
            contentType = ContentType.WEB,
            readingMode = ReadingMode.VERTICAL
        )
    }

    private fun writeTallImage(file: File) {
        val bitmap = Bitmap.createBitmap(TALL_IMAGE_WIDTH, TALL_IMAGE_HEIGHT, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            repeat(TALL_IMAGE_BANDS) { band ->
                paint.color = if (band % 2 == 0) TALL_IMAGE_DARK_COLOR else TALL_IMAGE_LIGHT_COLOR
                val top = band * TALL_IMAGE_HEIGHT.toFloat() / TALL_IMAGE_BANDS
                val bottom = (band + 1) * TALL_IMAGE_HEIGHT.toFloat() / TALL_IMAGE_BANDS
                canvas.drawRect(0f, top, TALL_IMAGE_WIDTH.toFloat(), bottom, paint)
            }
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun seedPdf(context: Context) {
        val fixtureDir = File(context.filesDir, FIXTURE_DIR)
        check(fixtureDir.mkdirs() || fixtureDir.isDirectory)
        val pdfFile = File(fixtureDir, PDF_FILE)
        val document = PdfDocument()
        try {
            val paint = Paint().apply { textSize = PDF_TEXT_SIZE }
            repeat(PDF_PAGE_COUNT) { page ->
                val pageInfo = PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, page + 1).create()
                val pdfPage = document.startPage(pageInfo)
                pdfPage.canvas.drawText("Benchmark PDF page ${page + 1}", PDF_TEXT_X, PDF_TITLE_Y, paint)
                pdfPage.canvas.drawText(PDF_BODY, PDF_TEXT_X, PDF_BODY_Y, paint)
                document.finishPage(pdfPage)
            }
            FileOutputStream(pdfFile).use(document::writeTo)
        } finally {
            document.close()
        }
        restoreCurrentItem(
            url = pdfFile.absolutePath,
            title = "Benchmark PDF",
            contentType = ContentType.PDF,
            readingMode = ReadingMode.VERTICAL
        )
    }

    private suspend fun restoreCurrentItem(
        url: String,
        title: String,
        contentType: ContentType,
        readingMode: ReadingMode
    ) {
        val item = LibraryItem(
            id = "benchmark-current",
            title = title,
            url = url,
            isCurrentlyReading = true,
            currentChapter = title,
            currentChapterUrl = url,
            totalChapters = 1,
            contentType = contentType,
            baseTitle = title,
            readingMode = readingMode,
            sourceName = SOURCE_NAME
        )
        libraryDao.insertItem(item)
        preferencesManager.batchUpdateLastRead(url, item.id)
    }

    private fun writeManifest(
        chapterUrl: String,
        title: String,
        elements: List<ContentElement>,
        images: List<FixtureImageRecord>
    ) {
        val manifest = FixtureManifest(
            schemaVersion = OFFLINE_SCHEMA_VERSION,
            chapterUrl = chapterUrl,
            title = title,
            elements = elements,
            images = images,
            complete = true,
            downloadedAtMs = System.currentTimeMillis()
        )
        val chapterDir = File(offlineRoot, CacheKeyUtils.keyFor(chapterUrl))
        check(chapterDir.mkdirs() || chapterDir.isDirectory)
        val target = File(chapterDir, MANIFEST_FILE)
        val temporary = File.createTempFile("manifest.", ".tmp", chapterDir)
        try {
            temporary.writeText(json.encodeToString(manifest))
            if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
        } finally {
            temporary.delete()
        }
    }

    private enum class BenchmarkFixture(val wireName: String) {
        LIBRARY_500("library500"),
        TEXT_500_PAGED("text500Paged"),
        TEXT_1000_PAGED("text1000Paged"),
        TEXT_1000_SCROLL("text1000Scroll"),
        MANHWA_TALL("manhwaTall"),
        PDF("pdf");

        companion object {
            fun fromWireName(wireName: String?): BenchmarkFixture? = entries.firstOrNull {
                it.wireName == wireName
            }
        }
    }

    @Serializable
    private data class FixtureManifest(
        val schemaVersion: Int,
        val chapterUrl: String,
        val title: String,
        val elements: List<ContentElement>,
        val images: List<FixtureImageRecord>,
        val complete: Boolean,
        val downloadedAtMs: Long
    )

    @Serializable
    private data class FixtureImageRecord(
        val url: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val bytes: Long
    )

    private companion object {
        private const val TAG = "BenchmarkFixture"
        private const val ACTION_SEED = "io.aatricks.easyreader.benchmark.SEED"
        private const val EXTRA_FIXTURE = "fixture"
        private const val FIXTURE_BASE_URL = "https://benchmark.easyreader"
        private const val LIBRARY_BASE_URL = "$FIXTURE_BASE_URL/library"
        private const val SERIES_TITLE = "Benchmark Series"
        private const val SOURCE_NAME = "Benchmark Source"
        private const val LIBRARY_CHAPTER_COUNT = 500
        private const val TEXT_500_COUNT = 500
        private const val TEXT_1000_COUNT = 1_000
        private const val WEB_OFFLINE_PIPELINE_VERSION = 3
        private const val OFFLINE_SCHEMA_VERSION = 1
        private const val MANIFEST_FILE = "manifest.json"
        private const val IMAGE_DIR = "images"
        private const val FIXTURE_DIR = "benchmark-fixtures"
        private const val PDF_FILE = "benchmark.pdf"
        private const val PDF_PAGE_COUNT = 20
        private const val PDF_WIDTH = 612
        private const val PDF_HEIGHT = 792
        private const val PDF_TEXT_SIZE = 18f
        private const val PDF_TEXT_X = 48f
        private const val PDF_TITLE_Y = 72f
        private const val PDF_BODY_Y = 112f
        private const val TALL_IMAGE_WIDTH = 512
        private const val TALL_IMAGE_HEIGHT = 24_576
        private const val TALL_IMAGE_BANDS = 24
        private const val JPEG_QUALITY = 82
        private val TALL_IMAGE_DARK_COLOR = Color.rgb(35, 45, 58)
        private val TALL_IMAGE_LIGHT_COLOR = Color.rgb(82, 99, 117)
        private const val PDF_BODY =
            "Deterministic content for release-derived PDF opening and page rendering measurement."
        private val json = Json { encodeDefaults = true }
    }
}
