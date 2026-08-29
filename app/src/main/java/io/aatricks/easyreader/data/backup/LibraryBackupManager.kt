package io.aatricks.easyreader.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.aatricks.easyreader.data.local.ReadingSessionDao
import io.aatricks.easyreader.data.model.ReadingSessionEntity
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingMode
import io.aatricks.easyreader.data.repository.LibraryRepository
import io.aatricks.easyreader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(
    val imported: Int,
    val duplicates: Int,
    val invalid: Int
)

@Singleton
@Suppress("InjectDispatcher")
class LibraryBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val readingSessionDao: ReadingSessionDao
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val items = libraryRepository.getAllItemsSnapshot()
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open output stream")

            val manifestItems = mutableListOf<LibraryItemBackup>()
            ZipOutputStream(out.buffered()).use { zip ->
                for (item in items) {
                    val bundledPath = if (item.contentType == ContentType.EPUB) {
                        tryBundleEpub(item, zip)
                    } else null
                    manifestItems += item.toBackup(bundledPath)
                }
                val manifestJson = json.encodeToString(
                    LibraryBackup(
                        schemaVersion = BACKUP_SCHEMA_VERSION,
                        exportedAt = System.currentTimeMillis(),
                        appVersionName = readAppVersionName(),
                        items = manifestItems,
                        readingSessions = readingSessionDao.getAllSessions().map {
                            ReadingSessionBackup(
                                novelKey = it.novelKey,
                                startedAt = it.startedAt,
                                endedAt = it.endedAt,
                                activeMillis = it.activeMillis,
                                chaptersCompleted = it.chaptersCompleted,
                                seeded = it.seeded
                            )
                        }
                    )
                )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            items.size
        }.onFailure { Log.e(TAG, "Library export failed", it) }
    }

    private fun tryBundleEpub(item: LibraryItem, zip: ZipOutputStream): String? {
        val safeId = sanitizeForFilename(item.id)
        val entryPath = "$EPUB_ENTRY_PREFIX$safeId.epub"
        return runCatching {
            val source = Uri.parse(item.url)
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("URI not readable: ${item.url}")
            input.use {
                zip.putNextEntry(ZipEntry(entryPath))
                it.copyTo(zip)
                zip.closeEntry()
            }
            entryPath
        }.onFailure { e ->
            Log.w(TAG, "Skipped bundling EPUB for ${item.title} (${item.url}): ${e.message}")
        }.getOrNull()
    }

    suspend fun importFrom(uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "backup_restore_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val manifestText = extractZipToTempDir(uri, tempDir)
                    ?: throw IOException("Backup is missing $MANIFEST_ENTRY")
                val manifest = json.decodeFromString<LibraryBackup>(manifestText)
                if (manifest.schemaVersion > BACKUP_SCHEMA_VERSION) {
                    throw IOException(
                        "Backup schema ${manifest.schemaVersion} is newer than supported " +
                            "(${BACKUP_SCHEMA_VERSION}). Update the app and try again."
                    )
                }
                restoreFromManifest(manifest, tempDir)
            } finally {
                tempDir.deleteRecursively()
            }
        }.onFailure { Log.e(TAG, "Library import failed", it) }
    }

    private fun extractZipToTempDir(uri: Uri, tempDir: File): String? {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open input stream")
        return input.use { ZipInputStream(it.buffered()).use { zip -> drainZip(zip, tempDir) } }
    }

    private fun drainZip(zip: ZipInputStream, tempDir: File): String? {
        var manifestText: String? = null
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            when {
                name == MANIFEST_ENTRY ->
                    manifestText = zip.readBytes().toString(Charsets.UTF_8)
                name.startsWith(EPUB_ENTRY_PREFIX) && !entry.isDirectory ->
                    File(tempDir, sanitizeForFilename(name.removePrefix(EPUB_ENTRY_PREFIX)))
                        .outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return manifestText
    }

    private suspend fun restoreFromManifest(manifest: LibraryBackup, tempDir: File): ImportSummary {
        val existingUrls = libraryRepository.getAllItemsSnapshot()
            .asSequence()
            .map { it.url }
            .toSet()
        val epubsDir = File(context.filesDir, "imported_epubs").apply { mkdirs() }
        val toInsert = mutableListOf<LibraryItem>()
        var duplicates = 0
        var invalid = 0
        for (backupItem in manifest.items) {
            when (val outcome = convertBackupItem(backupItem, tempDir, epubsDir, existingUrls)) {
                is ConvertOutcome.Insert -> toInsert += outcome.item
                ConvertOutcome.Duplicate -> duplicates++
                ConvertOutcome.Invalid -> invalid++
            }
        }
        if (toInsert.isNotEmpty()) {
            libraryRepository.restoreItems(toInsert)
        }

        if (manifest.readingSessions.isNotEmpty()) {
            val existingSessions = readingSessionDao.getAllSessions().map {
                Triple(it.novelKey, it.startedAt, it.activeMillis)
            }.toSet()

            val sessionsToInsert = manifest.readingSessions.filter { backupSession ->
                Triple(backupSession.novelKey, backupSession.startedAt, backupSession.activeMillis) !in existingSessions
            }.map { backupSession ->
                ReadingSessionEntity(
                    novelKey = backupSession.novelKey,
                    startedAt = backupSession.startedAt,
                    endedAt = backupSession.endedAt,
                    activeMillis = backupSession.activeMillis,
                    chaptersCompleted = backupSession.chaptersCompleted,
                    seeded = backupSession.seeded
                )
            }

            if (sessionsToInsert.isNotEmpty()) {
                readingSessionDao.insertAll(sessionsToInsert)
            }
        }

        return ImportSummary(imported = toInsert.size, duplicates = duplicates, invalid = invalid)
    }

    private sealed interface ConvertOutcome {
        data class Insert(val item: LibraryItem) : ConvertOutcome
        data object Duplicate : ConvertOutcome
        data object Invalid : ConvertOutcome
    }

    private fun convertBackupItem(
        backupItem: LibraryItemBackup,
        tempDir: File,
        epubsDir: File,
        existingUrls: Set<String>
    ): ConvertOutcome {
        val resolved = resolveItemUrl(backupItem, tempDir, epubsDir)
        return when {
            resolved == null -> {
                Log.w(TAG, "Dropping backup item ${backupItem.id} (${backupItem.title}): unresolved url/bundled EPUB")
                ConvertOutcome.Invalid
            }
            resolved.url in existingUrls -> ConvertOutcome.Duplicate
            else -> runCatching { backupItem.toEntity(resolved.url, resolved.fileVerified) }
                .fold(
                    onSuccess = { ConvertOutcome.Insert(it) },
                    onFailure = { e ->
                        Log.w(TAG, "Dropping backup item ${backupItem.id} (${backupItem.title}): ${e.message}")
                        ConvertOutcome.Invalid
                    }
                )
        }
    }

    private fun resolveItemUrl(item: LibraryItemBackup, tempDir: File, epubsDir: File): ResolvedItem? {
        item.bundledEpubPath?.let { bundled ->
            return installBundledEpub(item.id, bundled, tempDir, epubsDir)
        }
        val url = item.url.takeIf { it.isNotBlank() } ?: return null
        // WEB / PDF backups don't ride bundled files along, so we can't honor the
        // downloaded flag — the cached HTML/media weren't exported.
        return ResolvedItem(url = url, fileVerified = false)
    }

    private fun installBundledEpub(id: String, bundledPath: String, tempDir: File, epubsDir: File): ResolvedItem? {
        val source = File(tempDir, sanitizeForFilename(bundledPath.removePrefix(EPUB_ENTRY_PREFIX)))
        if (!source.exists()) {
            Log.w(TAG, "Bundled EPUB missing from ZIP: $bundledPath")
            return null
        }
        val dest = File(epubsDir, "${sanitizeForFilename(id)}.epub")
        return runCatching { source.copyTo(dest, overwrite = true) }
            .onFailure { Log.w(TAG, "Could not move bundled EPUB to ${dest.absolutePath}", it) }
            .map { ResolvedItem(url = Uri.fromFile(dest).toString(), fileVerified = dest.exists()) }
            .getOrNull()
    }

    private data class ResolvedItem(val url: String, val fileVerified: Boolean)

    private fun readAppVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        private const val TAG = "LibraryBackupManager"
        internal const val MAX_PROGRESS = 100
        private val unsafeChars = Regex("[^A-Za-z0-9_.\\-]")

        internal fun sanitizeForFilename(name: String): String =
            name.replace(unsafeChars, "_").ifBlank { "item" }
    }
}

private fun LibraryItem.toBackup(bundledPath: String?): LibraryItemBackup = LibraryItemBackup(
    id = id,
    title = title,
    url = url,
    timestamp = timestamp,
    progress = progress,
    isCurrentlyReading = isCurrentlyReading,
    currentChapter = currentChapter,
    currentChapterUrl = currentChapterUrl,
    totalChapters = totalChapters,
    contentType = contentType.name,
    dateAdded = dateAdded,
    lastRead = lastRead,
    isDownloading = isDownloading,
    lastScrollPosition = lastScrollPosition,
    lastReadIndex = lastReadIndex,
    lastReadElementKey = lastReadElementKey,
    lastReadOffsetFraction = lastReadOffsetFraction.takeUnless { it == io.aatricks.easyreader.data.model.FRACTION_UNKNOWN },
    hasUpdates = hasUpdates,
    chapterSummaries = chapterSummaries,
    baseTitle = baseTitle,
    readingMode = readingMode.name,
    baseNovelUrl = baseNovelUrl,
    sourceName = sourceName,
    isDownloaded = isDownloaded,
    downloadedAt = downloadedAt,
    bundledEpubPath = bundledPath,
    coverImageUrl = coverImageUrl
)

private fun LibraryItemBackup.toEntity(rewrittenUrl: String, fileVerified: Boolean): LibraryItem = LibraryItem(
    id = id,
    title = title,
    url = rewrittenUrl,
    timestamp = timestamp,
    progress = progress.coerceIn(0, LibraryBackupManager.MAX_PROGRESS),
    isCurrentlyReading = false,
    currentChapter = currentChapter,
    currentChapterUrl = currentChapterUrl,
    totalChapters = totalChapters,
    contentType = runCatching { ContentType.valueOf(contentType) }.getOrDefault(ContentType.WEB),
    dateAdded = dateAdded,
    lastRead = lastRead,
    isDownloading = false,
    lastScrollPosition = lastScrollPosition,
    lastReadIndex = lastReadIndex,
    lastReadElementKey = lastReadElementKey,
    lastReadOffsetFraction = lastReadOffsetFraction ?: io.aatricks.easyreader.data.model.FRACTION_UNKNOWN,
    hasUpdates = hasUpdates,
    chapterSummaries = chapterSummaries,
    baseTitle = baseTitle.ifBlank {
        TextUtils.extractBaseTitle(
            title,
            runCatching { ContentType.valueOf(contentType) }.getOrDefault(ContentType.WEB)
        )
    },
    readingMode = runCatching { ReadingMode.valueOf(readingMode) }.getOrDefault(ReadingMode.VERTICAL),
    baseNovelUrl = baseNovelUrl,
    sourceName = sourceName,
    isDownloaded = isDownloaded && fileVerified,
    downloadedAt = if (isDownloaded && fileVerified) downloadedAt else null,
    coverImageUrl = coverImageUrl
)
