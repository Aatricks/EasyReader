package io.aatricks.easyreader.data.backup

import kotlinx.serialization.Serializable

const val BACKUP_SCHEMA_VERSION = 3
const val MANIFEST_ENTRY = "manifest.json"
const val EPUB_ENTRY_PREFIX = "epubs/"

@Serializable
data class SettingsBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersionName: String,
    val reader: ReaderSettingsPayload,
    val scrollFinishedSeries: List<String> = emptyList(),
    val scrollUnlockedMilestones: Map<String, Long> = emptyMap(),
    val scrollHistorySeeded: Boolean = false
)

@Serializable
data class ReaderSettingsPayload(
    val fontSize: Float,
    val lineHeight: Float,
    val fontFamily: String,
    val margins: Int,
    val verticalMargins: Int = 0,
    val paragraphSpacing: Float,
    val readerTheme: String,
    val accentTheme: String,
    val brightness: Float = 1.0f
)

@Serializable
data class LibraryBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersionName: String,
    val items: List<LibraryItemBackup>,
    val readingSessions: List<ReadingSessionBackup> = emptyList()
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
    val lastReadElementKey: String = "",
    val lastReadOffsetFraction: Float? = null,
    // Legacy schema-v1 raw-pixel anchor. Kept readable so v1 backups still parse; we use it as a
    // last-resort hint (the post-unification restore path derives index from percent and ignores
    // pixel offsets, but recording it lets us spot v1 imports in logs if needed).
    val lastReadOffset: Int = 0,
    val hasUpdates: Boolean = false,
    val chapterSummaries: Map<String, String> = emptyMap(),
    val baseTitle: String,
    val readingMode: String,
    val baseNovelUrl: String,
    val sourceName: String,
    val isDownloaded: Boolean = false,
    val downloadedAt: Long? = null,
    val bundledEpubPath: String? = null,
    val coverImageUrl: String = ""
)

@Serializable
data class ReadingSessionBackup(
    val novelKey: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeMillis: Long,
    val chaptersCompleted: Int,
    val seeded: Boolean = false
)
