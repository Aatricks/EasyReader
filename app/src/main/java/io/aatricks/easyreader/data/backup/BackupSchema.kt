package io.aatricks.easyreader.data.backup

import kotlinx.serialization.Serializable

const val BACKUP_SCHEMA_VERSION = 1
const val MANIFEST_ENTRY = "manifest.json"
const val EPUB_ENTRY_PREFIX = "epubs/"

@Serializable
data class SettingsBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersionName: String,
    val reader: ReaderSettingsPayload
)

@Serializable
data class ReaderSettingsPayload(
    val fontSize: Float,
    val lineHeight: Float,
    val fontFamily: String,
    val margins: Int,
    val paragraphSpacing: Float,
    val readerTheme: String,
    val accentTheme: String
)

@Serializable
data class LibraryBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersionName: String,
    val items: List<LibraryItemBackup>
)

@Serializable
data class LibraryItemBackup(
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long,
    val progress: Int,
    val isCurrentlyReading: Boolean,
    val currentChapter: String,
    val currentChapterUrl: String,
    val totalChapters: Int,
    val contentType: String,
    val dateAdded: Long,
    val lastRead: Long,
    val isDownloading: Boolean = false,
    val lastScrollPosition: Float,
    val lastReadIndex: Int,
    val lastReadOffset: Int,
    val lastReadOffsetFraction: Float? = null,
    val hasUpdates: Boolean = false,
    val chapterSummaries: Map<String, String> = emptyMap(),
    val baseTitle: String,
    val readingMode: String,
    val baseNovelUrl: String,
    val sourceName: String,
    val isDownloaded: Boolean = false,
    val downloadedAt: Long? = null,
    val bundledEpubPath: String? = null
)
