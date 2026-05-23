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
    private val imageCache: ImageCache
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class ChapterPayload(
        val title: String?,
        val elements: List<ContentElement>,
        val imageUrls: List<String>
    )

    suspend fun downloadChapter(
        url: String,
        document: Document,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): PrefetchResult = withContext(Dispatchers.IO) {
        val payload = parsePayload(url, document)
        if (payload.imageUrls.isEmpty()) {
            clear(url)
            return@withContext PrefetchResult(
                url = url,
                htmlCached = false,
                totalImages = 0,
                cachedImages = 0,
                isComplete = false,
                isRetryable = true,
                isPersistentDownload = false
            )
        }

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

        emitProgress(url, records, inProgress = true, onProgress = onProgress)
        val downloaded = downloadMissingImages(url, records, onProgress)
        val latestRecords = records.map { record ->
            downloaded[record.url] ?: existingRecord(record, fileFor(url, record.fileName)) ?: record
        }
        val complete = latestRecords.all { record ->
            val file = fileFor(url, record.fileName)
            file.exists() && ImageIntegrity.isValidImageFile(file)
        }
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

        inspect(url).copy(isInProgress = false, isRetryable = !complete)
    }

    fun loadContent(url: String): ContentResult.Success? {
        val manifest = readManifest(url) ?: return null
        if (!manifest.complete || !validateManifestFiles(url, manifest)) return null
        val recordsByUrl = manifest.images.associateBy { it.url }
        return ContentResult.Success(
            elements = manifest.elements.rewriteImages(url, recordsByUrl),
            title = manifest.title,
            url = url
        )
    }

    suspend fun inspect(url: String): PrefetchResult = withContext(Dispatchers.IO) {
        val manifest = readManifest(url)
            ?: return@withContext PrefetchResult(
                url = url,
                htmlCached = false,
                totalImages = 0,
                cachedImages = 0,
                isComplete = false,
                isRetryable = true,
                isPersistentDownload = false
            )

        val cached = manifest.images.count { record ->
            ImageIntegrity.isValidImageFile(fileFor(url, record.fileName))
        }
        val complete = manifest.complete &&
            manifest.images.isNotEmpty() &&
            cached == manifest.images.size
        PrefetchResult(
            url = url,
            htmlCached = true,
            totalImages = manifest.images.size,
            cachedImages = cached,
            isComplete = complete,
            isRetryable = !complete,
            isPersistentDownload = true,
            hasPermanentFailures = false
        )
    }

    fun hasChapterDir(url: String): Boolean = chapterDir(url).exists()

    fun hasCompleteChapter(url: String): Boolean {
        val manifest = readManifest(url) ?: return false
        return manifest.complete && validateManifestFiles(url, manifest)
    }

    fun hasCompleteManifestRecord(url: String): Boolean =
        readManifest(url)?.complete == true

    fun hasImage(url: String, imageUrl: String): Boolean {
        val manifest = readManifest(url) ?: return false
        val record = manifest.images.firstOrNull { it.url == imageUrl } ?: return false
        return ImageIntegrity.isValidImageFile(fileFor(url, record.fileName))
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
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ): Map<String, ImageRecord> = supervisorScope {
        val semaphore = Semaphore(MAX_CONCURRENT_IMAGE_DOWNLOADS)
        records.map { record ->
            async {
                semaphore.withPermit {
                    val existing = existingRecord(record, fileFor(pageUrl, record.fileName))
                    if (existing != null) {
                        onProgress?.let { emitProgress(pageUrl, records, inProgress = true, onProgress = it) }
                        return@withPermit existing.url to existing
                    }
                    val downloaded = downloadImage(pageUrl, record)
                    if (downloaded != null) {
                        onProgress?.let { emitProgress(pageUrl, records, inProgress = true, onProgress = it) }
                        downloaded.url to downloaded
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private suspend fun downloadImage(pageUrl: String, record: ImageRecord): ImageRecord? {
        val target = fileFor(pageUrl, record.fileName)
        val temp = File(target.parentFile, "${target.name}.${java.util.UUID.randomUUID()}.tmp")
        target.parentFile?.mkdirs()
        imageCache.findExistingCachedMediaFile(record.url)?.let { cached ->
            return runCatching {
                cached.copyTo(target, overwrite = true)
                if (!ImageIntegrity.isValidImageFile(target)) {
                    target.delete()
                    return@runCatching null
                }
                val bounds = ImageBoundsParser.parse(target)
                record.copy(
                    width = bounds?.first ?: 0,
                    height = bounds?.second ?: 0,
                    bytes = target.length()
                )
            }.getOrNull()
        }
        val result = imageDownloader.executeImageRequest(
            imageUrl = record.url,
            pageUrl = pageUrl,
            priority = ImageRequestPriority.USER_REQUESTED,
            destinationFile = temp
        )
        if (result !is ImageFetchResult.Success) {
            temp.delete()
            return null
        }
        if (!ImageIntegrity.isValidImageFile(temp)) {
            Log.w(TAG, "invalid offline image url=${UrlSanitizer.sanitize(record.url)}")
            temp.delete()
            return null
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (!ImageIntegrity.isValidImageFile(target)) {
            target.delete()
            return null
        }
        val bounds = ImageBoundsParser.parse(target)
        return record.copy(
            width = bounds?.first ?: 0,
            height = bounds?.second ?: 0,
            bytes = target.length()
        )
    }

    private suspend fun emitProgress(
        url: String,
        records: List<ImageRecord>,
        inProgress: Boolean,
        onProgress: (suspend (PrefetchResult) -> Unit)?
    ) {
        if (onProgress == null) return
        val cached = records.count { record ->
            ImageIntegrity.isValidImageFile(fileFor(url, record.fileName))
        }
        onProgress(
            PrefetchResult(
                url = url,
                htmlCached = true,
                totalImages = records.size,
                cachedImages = cached,
                isComplete = false,
                isInProgress = inProgress,
                isRetryable = true,
                isPersistentDownload = true
            )
        )
    }

    private fun existingRecord(record: ImageRecord, file: File): ImageRecord? {
        if (!ImageIntegrity.isValidImageFile(file)) return null
        val bounds = ImageBoundsParser.parse(file)
        return record.copy(
            width = bounds?.first ?: record.width,
            height = bounds?.second ?: record.height,
            bytes = file.length()
        )
    }

    private fun validateManifestFiles(url: String, manifest: Manifest): Boolean =
        manifest.images.isNotEmpty() &&
            manifest.images.all { ImageIntegrity.isValidImageFile(fileFor(url, it.fileName)) }

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
    private data class Manifest(
        val schemaVersion: Int,
        val chapterUrl: String,
        val title: String?,
        val elements: List<ContentElement>,
        val images: List<ImageRecord>,
        val complete: Boolean,
        val downloadedAtMs: Long?
    )

    @Serializable
    private data class ImageRecord(
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
