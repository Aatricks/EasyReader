package io.aatricks.easyreader.data.repository.content

import android.util.Log
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ContentResult
import io.aatricks.easyreader.data.model.ImageRequestPriority
import io.aatricks.easyreader.data.model.PrefetchResult
import io.aatricks.easyreader.data.repository.HtmlParser
import io.aatricks.easyreader.di.WebOfflineDownloadsDir
import io.aatricks.easyreader.util.CacheKeyUtils
import io.aatricks.easyreader.util.FileSizeUtils
import io.aatricks.easyreader.util.ImageIntegrity
import io.aatricks.easyreader.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebOfflineChapterStore @Inject constructor(
    @WebOfflineDownloadsDir private val rootDir: File,
    private val htmlParser: HtmlParser,
    private val imageDownloader: ImageDownloader,
    private val imageCache: ImageCache,
    private val permanentFailureStore: PermanentFailureStore
) {
    internal var imageValidator: (File) -> Boolean = ImageIntegrity::isValidImageFile

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class ChapterPayload(
        val title: String?,
        val elements: List<ContentElement>,
        val imageUrls: List<String>
    )

    internal data class OfflineChapterInspection(
        val chapterUrl: String,
        internal val manifest: Manifest?,
        val resolvedFiles: Map<String, File>,
        val validationResults: Map<String, Boolean>,
        val result: PrefetchResult
    ) {
        val hasCompleteManifestRecord: Boolean
            get() = manifest?.complete == true
    }

    private sealed interface DownloadResult {
        data class Success(val record: ImageRecord) : DownloadResult
        data class Failure(val retryable: Boolean) : DownloadResult
    }

    private suspend fun handleZeroImageChapter(
        url: String,
        document: Document,
        payload: ChapterPayload
    ): PrefetchResult? {
        if (payload.imageUrls.isNotEmpty()) return null
        return if (ChapterDocumentClassifier.isRenderableTextChapter(document, payload.elements)) {
            val now = System.currentTimeMillis()
            writeManifest(
                url = url,
                manifest = Manifest(
                    schemaVersion = SCHEMA_VERSION,
                    chapterUrl = url,
                    title = payload.title,
                    elements = payload.elements,
                    images = emptyList(),
                    complete = true,
                    downloadedAtMs = now
                )
            )
            PrefetchResult(
                url = url,
                htmlCached = true,
                totalImages = 0,
                cachedImages = 0,
                isComplete = true,
                isInProgress = false,
                isRetryable = false,
                isPersistentDownload = true,
                hasPermanentFailures = false
            )
        } else {
            val existing = inspect(url)
            existing.copy(
                isInProgress = false,
                isRetryable = !existing.isComplete
            )
        }
    }

    suspend fun downloadChapter(
        url: String,
        document: Document,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): PrefetchResult = withContext(Dispatchers.IO) {
        val payload = parsePayload(url, document)
        keepExistingOnShrunkParse(url, payload.imageUrls.size)?.let { return@withContext it }
        handleZeroImageChapter(url, document, payload)?.let { return@withContext it }

        val chapterDir = chapterDir(url)
        val imageDir = File(chapterDir, IMAGE_DIR)
        imageDir.mkdirs()
        val records = payload.imageUrls.map { imageUrl ->
            ImageRecord(
                url = imageUrl,
                fileName = fileNameFor(imageUrl),
                width = 0,
                height = 0,
                bytes = 0L
            )
        }
        writeManifest(
            url = url,
            manifest = Manifest(
                schemaVersion = SCHEMA_VERSION,
                chapterUrl = url,
                title = payload.title,
                elements = payload.elements,
                images = records,
                complete = false,
                downloadedAtMs = null
            )
        )

        val progressGate = if (onProgress != null) {
            OfflineProgressGate(
                totalImages = records.size,
                onEmit = { count, _ ->
                    emitProgressCount(url, records.size, count, inProgress = true, onProgress = onProgress)
                }
            )
        } else null

        progressGate?.emitInitial(0)
        val (downloaded, completedCount) = downloadMissingImages(url, records, progressGate)
        progressGate?.onImageCompleted(completedCount)

        val latestRecords = records.map { record ->
            downloaded[record.url] ?: existingRecord(record, fileFor(url, record.fileName)) ?: record
        }
        val complete = downloaded.size == records.size
        val finalManifest = Manifest(
            schemaVersion = SCHEMA_VERSION,
            chapterUrl = url,
            title = payload.title,
            elements = payload.elements,
            images = latestRecords,
            complete = complete,
            downloadedAtMs = if (complete) System.currentTimeMillis() else null
        )
        writeManifest(url, finalManifest)

        val finalResult = inspectChapter(url).result.copy(isInProgress = false)
        onProgress?.invoke(finalResult)
        finalResult
    }

    /**
     * The single definition of "the reader can serve this chapter entirely from disk".
     * The badge (via [inspectChapter]) and the reader (via [loadContent]) both route through
     * it. They used to disagree: inspect counted image files on disk while loadContent also
     * demanded the manifest's `complete` flag. Every download writes that flag as `false`
     * first and flips it only after the last image lands, so any run stopped in between left
     * a chapter whose files were all present but whose flag said otherwise — a permanent
     * "Downloaded" badge the reader refused to open, which the worker's already-complete fast
     * path then re-confirmed instead of repairing.
     *
     * Files on disk are the truth. The flag only decides image-less text chapters, which have
     * no files to count.
     */
    private fun isFullyPresent(manifest: Manifest, validationResults: Map<String, Boolean>): Boolean =
        if (manifest.images.isEmpty()) {
            manifest.complete
        } else {
            manifest.images.all { validationResults[it.url] == true }
        }

    fun loadContent(url: String): ContentResult.Success? {
        val manifest = readManifest(url)
            ?.takeIf { isFullyPresent(it, validationResultsFor(url, it)) }
            ?: return null
        val recordsByUrl = manifest.images.associateBy { it.url }
        return ContentResult.Success(
            elements = manifest.elements.rewriteImages(url, recordsByUrl),
            title = manifest.title,
            url = url
        )
    }

    internal fun loadContent(inspection: OfflineChapterInspection): ContentResult.Success? {
        val manifest = inspection.manifest
            ?.takeIf { isFullyPresent(it, inspection.validationResults) }
            ?: return null
        val recordsByUrl = manifest.images.associateBy { it.url }
        return ContentResult.Success(
            elements = manifest.elements.rewriteImages(inspection.chapterUrl, recordsByUrl),
            title = manifest.title,
            url = inspection.chapterUrl
        )
    }

    suspend fun inspect(url: String): PrefetchResult = inspectChapter(url).result

    internal suspend fun inspectChapter(url: String): OfflineChapterInspection = withContext(Dispatchers.IO) {
        val manifest = readManifest(url)
            ?: return@withContext OfflineChapterInspection(
                chapterUrl = url,
                manifest = null,
                resolvedFiles = emptyMap(),
                validationResults = emptyMap(),
                result = PrefetchResult(
                    url = url,
                    htmlCached = false,
                    totalImages = 0,
                    cachedImages = 0,
                    isComplete = false,
                    isRetryable = true,
                    isPersistentDownload = false
                )
            )

        val fresh = permanentFailureStore.load(url, System.currentTimeMillis() - PermanentFailureStore.DEFAULT_TTL_MS)
        val resolvedFiles = manifest.images.associate { record ->
            record.url to fileFor(url, record.fileName)
        }
        val validationResults = resolvedFiles.mapValues { (_, file) -> imageValidator(file) }
        val onDisk = validationResults.values.count { it }
        val accountedPermanent = manifest.images.count { record ->
            record.url in fresh && validationResults[record.url] != true
        }
        // Complete means "nothing left to attempt", which is a weaker claim than
        // isFullyPresent: images the CDN permanently refuses are accounted for here so the
        // download worker stops retrying. hasPermanentFailures is what keeps those chapters
        // off the Downloaded badge.
        val isComplete = isFullyPresent(manifest, validationResults) ||
            (manifest.images.isNotEmpty() && onDisk + accountedPermanent == manifest.images.size)

        OfflineChapterInspection(
            chapterUrl = url,
            manifest = manifest,
            resolvedFiles = resolvedFiles,
            validationResults = validationResults,
            result = PrefetchResult(
                url = url,
                htmlCached = true,
                totalImages = manifest.images.size,
                cachedImages = onDisk,
                isComplete = isComplete,
                isRetryable = !isComplete,
                isPersistentDownload = true,
                hasPermanentFailures = accountedPermanent > 0
            )
        )
    }

    fun hasChapterDir(url: String): Boolean = chapterDir(url).exists()

    fun hasCompleteChapter(url: String): Boolean {
        val manifest = readManifest(url) ?: return false
        return isFullyPresent(manifest, validationResultsFor(url, manifest))
    }

    fun hasCompleteManifestRecord(url: String): Boolean =
        readManifest(url)?.complete == true

    fun hasImage(url: String, imageUrl: String): Boolean {
        val manifest = readManifest(url) ?: return false
        val record = manifest.images.firstOrNull { it.url == imageUrl } ?: return false
        return imageValidator(fileFor(url, record.fileName))
    }

    fun clear(url: String) {
        chapterDir(url).deleteRecursively()
    }

    fun clearAll() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
    }

    fun sizeBytes(): Long = FileSizeUtils.calculateDirectorySize(rootDir)

    private fun parsePayload(url: String, document: Document): ChapterPayload {
        val elements = htmlParser.parse(document, url)
        val imageUrls = extractImageUrls(elements)
            .filter { it.startsWith("http") }
            .distinct()
        return ChapterPayload(
            title = document.title().takeIf { it.isNotBlank() },
            elements = elements,
            imageUrls = imageUrls
        )
    }

    private suspend fun downloadMissingImages(
        pageUrl: String,
        records: List<ImageRecord>,
        progressGate: OfflineProgressGate?
    ): Pair<Map<String, ImageRecord>, Int> = supervisorScope {
        val semaphore = Semaphore(MAX_CONCURRENT_IMAGE_DOWNLOADS)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val results = records.map { record ->
            async {
                semaphore.withPermit {
                    val existing = existingRecord(record, fileFor(pageUrl, record.fileName))
                    if (existing != null) {
                        val count = completedCount.incrementAndGet()
                        progressGate?.onImageCompleted(count)
                        return@withPermit record.url to DownloadResult.Success(existing)
                    }
                    val downloaded = downloadImage(pageUrl, record)
                    if (downloaded is DownloadResult.Success) {
                        val count = completedCount.incrementAndGet()
                        progressGate?.onImageCompleted(count)
                    }
                    record.url to downloaded
                }
            }
        }.awaitAll()

        val downloadedMap = mutableMapOf<String, ImageRecord>()
        val permanentUrls = mutableListOf<String>()

        for ((url, res) in results) {
            when (res) {
                is DownloadResult.Success -> {
                    downloadedMap[url] = res.record
                }
                is DownloadResult.Failure -> {
                    if (!res.retryable) {
                        permanentUrls.add(url)
                    }
                }
            }
        }

        if (permanentUrls.isNotEmpty()) {
            permanentFailureStore.record(pageUrl, permanentUrls, System.currentTimeMillis())
        }

        downloadedMap to completedCount.get()
    }

    private suspend fun downloadImage(pageUrl: String, record: ImageRecord): DownloadResult {
        val target = fileFor(pageUrl, record.fileName)
        val temp = File(target.parentFile, "${target.name}.${java.util.UUID.randomUUID()}.tmp")
        target.parentFile?.mkdirs()

        val cachedRecord = imageCache.findExistingCachedMediaFile(record.url)?.let { cachedFile ->
            runCatching {
                cachedFile.copyTo(target, overwrite = true)
                if (imageValidator(target)) {
                    val bounds = ImageBoundsParser.parse(target)
                    record.copy(
                        width = bounds?.first ?: 0,
                        height = bounds?.second ?: 0,
                        bytes = target.length()
                    )
                } else {
                    target.delete()
                    null
                }
            }.getOrNull()
        }

        if (cachedRecord != null) {
            return DownloadResult.Success(cachedRecord)
        }

        return downloadImageFromNetwork(pageUrl, record, target, temp)
    }

    private suspend fun downloadImageFromNetwork(
        pageUrl: String,
        record: ImageRecord,
        target: File,
        temp: File
    ): DownloadResult {
        val result = imageDownloader.executeImageRequest(
            imageUrl = record.url,
            pageUrl = pageUrl,
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = temp
        )
        return when {
            result !is ImageFetchResult.Success -> {
                temp.delete()
                DownloadResult.Failure(retryable = result.isRetryable())
            }
            !imageValidator(temp) -> {
                Log.w(TAG, "invalid offline image url=${UrlSanitizer.sanitize(record.url)}")
                temp.delete()
                DownloadResult.Failure(retryable = true)
            }
            else -> {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                val bounds = ImageBoundsParser.parse(target)
                DownloadResult.Success(
                    record.copy(
                        width = bounds?.first ?: 0,
                        height = bounds?.second ?: 0,
                        bytes = target.length()
                    )
                )
            }
        }
    }

    private suspend fun emitProgressCount(
        url: String,
        totalImages: Int,
        cachedCount: Int,
        inProgress: Boolean,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ) {
        if (onProgress == null) return
        onProgress(
            PrefetchResult(
                url = url,
                htmlCached = true,
                totalImages = totalImages,
                cachedImages = cachedCount,
                isComplete = false,
                isInProgress = inProgress,
                isRetryable = true,
                isPersistentDownload = true
            )
        )
    }

    private fun existingRecord(record: ImageRecord, file: File): ImageRecord? {
        if (!file.exists() || !imageValidator(file)) return null
        val bounds = ImageBoundsParser.parse(file)
        return record.copy(
            width = bounds?.first ?: record.width,
            height = bounds?.second ?: record.height,
            bytes = file.length()
        )
    }

    private fun validationResultsFor(url: String, manifest: Manifest): Map<String, Boolean> =
        manifest.images.associate { it.url to imageValidator(fileFor(url, it.fileName)) }

    /**
     * A re-download must never shrink a chapter that is already fully on disk. A degraded
     * parse — a Cloudflare interstitial cached as the chapter HTML, or a JS page list the
     * extractor stopped recognising after a site markup change — would otherwise overwrite a
     * 40-page manifest with a 3-page one and still report complete, leaving a Downloaded
     * badge on a chapter that opens short.
     */
    private suspend fun keepExistingOnShrunkParse(url: String, parsedImageCount: Int): PrefetchResult? {
        val inspection = inspectChapter(url)
        val manifest = inspection.manifest
        val existingCount = manifest?.images?.size ?: 0
        val keepExisting = manifest != null &&
            existingCount > parsedImageCount &&
            isFullyPresent(manifest, inspection.validationResults)
        if (!keepExisting) return null
        Log.w(
            TAG,
            "keeping $existingCount-image manifest over $parsedImageCount-image reparse " +
                "url=${UrlSanitizer.sanitize(url)}"
        )
        return inspection.result.copy(isInProgress = false)
    }

    private fun readManifest(url: String): Manifest? {
        val file = manifestFile(url)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<Manifest>(file.readText())
                .takeIf { it.schemaVersion == SCHEMA_VERSION && it.chapterUrl == url }
        }.onFailure {
            Log.w(TAG, "manifest read failed url=${UrlSanitizer.sanitize(url)} message=${it.message}")
        }.getOrNull()
    }

    private fun writeManifest(url: String, manifest: Manifest) {
        val file = manifestFile(url)
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            temp.writeText(json.encodeToString(manifest))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun extractImageUrls(elements: List<ContentElement>): List<String> =
        elements.flatMap { element ->
            when (element) {
                is ContentElement.Image -> listOf(element.url)
                is ContentElement.ImageGroup -> element.images.map { it.url }
                is ContentElement.PageContent -> extractImageUrls(element.elements)
                else -> emptyList()
            }
        }

    private fun List<ContentElement>.rewriteImages(
        chapterUrl: String,
        recordsByUrl: Map<String, ImageRecord>
    ): List<ContentElement> = map { it.rewriteImages(chapterUrl, recordsByUrl) }

    private fun ContentElement.rewriteImages(
        chapterUrl: String,
        recordsByUrl: Map<String, ImageRecord>
    ): ContentElement =
        when (this) {
            is ContentElement.Image -> {
                val record = recordsByUrl[url]
                if (record == null) {
                    this
                } else {
                    copy(
                        url = fileFor(chapterUrl, record.fileName).toURI().toString(),
                        width = record.width.takeIf { it > 0 } ?: width,
                        height = record.height.takeIf { it > 0 } ?: height
                    )
                }
            }
            is ContentElement.ImageGroup -> copy(images = images.map { image ->
                val record = recordsByUrl[image.url]
                if (record == null) {
                    image
                } else {
                    image.copy(
                        url = fileFor(chapterUrl, record.fileName).toURI().toString(),
                        width = record.width.takeIf { it > 0 } ?: image.width,
                        height = record.height.takeIf { it > 0 } ?: image.height
                    )
                }
            })
            is ContentElement.PageContent -> copy(elements = elements.rewriteImages(chapterUrl, recordsByUrl))
            else -> this
        }

    private fun chapterDir(url: String): File = File(rootDir, CacheKeyUtils.keyFor(url))

    private fun manifestFile(url: String): File = File(chapterDir(url), MANIFEST_FILE)

    private fun fileFor(chapterUrl: String, fileName: String): File =
        File(File(chapterDir(chapterUrl), IMAGE_DIR), fileName)

    private fun fileNameFor(imageUrl: String): String =
        "${CacheKeyUtils.keyFor(imageUrl)}.${extensionFor(imageUrl)}"

    private fun extensionFor(imageUrl: String): String {
        val clean = imageUrl.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        val ext = clean.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> ext
            else -> "img"
        }
    }

    @Serializable
    internal data class Manifest(
        val schemaVersion: Int,
        val chapterUrl: String,
        val title: String?,
        val elements: List<ContentElement>,
        val images: List<ImageRecord>,
        val complete: Boolean,
        val downloadedAtMs: Long?
    )

    @Serializable
    internal data class ImageRecord(
        val url: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val bytes: Long
    )

    private companion object {
        private const val TAG = "WebOfflineStore"
        private const val SCHEMA_VERSION = 1
        private const val MANIFEST_FILE = "manifest.json"
        private const val IMAGE_DIR = "images"
        private const val MAX_CONCURRENT_IMAGE_DOWNLOADS = 4
    }
}
