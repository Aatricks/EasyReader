package io.aatricks.easyreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.aatricks.easyreader.data.model.ChapterImageStateEntity
import io.aatricks.easyreader.data.model.ImageDimensionEntity
import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.ReadingSessionEntity

@Database(
    entities = [
        LibraryItem::class,
        ChapterImageStateEntity::class,
        ImageDimensionEntity::class,
        ReadingSessionEntity::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun chapterImageStateDao(): ChapterImageStateDao
    abstract fun imageDimensionDao(): ImageDimensionDao
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        private const val DB_VERSION_9 = 9
        private const val DB_VERSION_10 = 10
        private const val DB_VERSION_11 = 11


        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove the redundant 'type' column by recreating the table
                db.execSQL("""
                    CREATE TABLE library_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        progress INTEGER NOT NULL DEFAULT 0,
                        isCurrentlyReading INTEGER NOT NULL DEFAULT 0,
                        isSelected INTEGER NOT NULL DEFAULT 0,
                        currentChapter TEXT NOT NULL DEFAULT '',
                        currentChapterUrl TEXT NOT NULL DEFAULT '',
                        totalChapters INTEGER NOT NULL DEFAULT 0,
                        contentType TEXT NOT NULL DEFAULT 'WEB',
                        dateAdded INTEGER NOT NULL DEFAULT 0,
                        lastRead INTEGER NOT NULL DEFAULT 0,
                        isDownloading INTEGER NOT NULL DEFAULT 0,
                        lastScrollPosition REAL NOT NULL DEFAULT 0,
                        lastReadIndex INTEGER NOT NULL DEFAULT 0,
                        lastReadOffset INTEGER NOT NULL DEFAULT 0,
                        hasUpdates INTEGER NOT NULL DEFAULT 0,
                        chapterSummaries TEXT NOT NULL DEFAULT '{}',
                        baseTitle TEXT NOT NULL DEFAULT '',
                        readingMode TEXT NOT NULL DEFAULT 'VERTICAL',
                        baseNovelUrl TEXT NOT NULL DEFAULT '',
                        sourceName TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO library_items_new (
                        id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                        lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                        baseNovelUrl, sourceName
                    ) SELECT
                        id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                        lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                        baseNovelUrl, sourceName
                    FROM library_items
                """.trimIndent())
                db.execSQL("DROP TABLE library_items")
                db.execSQL("ALTER TABLE library_items_new RENAME TO library_items")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_items ADD COLUMN lastReadOffsetFraction REAL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate table to add indices and handle potential duplicates by URL
                db.execSQL("""
                    CREATE TABLE library_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        progress INTEGER NOT NULL,
                        isCurrentlyReading INTEGER NOT NULL,
                        isSelected INTEGER NOT NULL,
                        currentChapter TEXT NOT NULL,
                        currentChapterUrl TEXT NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        contentType TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        lastRead INTEGER NOT NULL,
                        isDownloading INTEGER NOT NULL,
                        lastScrollPosition REAL NOT NULL,
                        lastReadIndex INTEGER NOT NULL,
                        lastReadOffset INTEGER NOT NULL,
                        lastReadOffsetFraction REAL,
                        hasUpdates INTEGER NOT NULL,
                        chapterSummaries TEXT NOT NULL,
                        baseTitle TEXT NOT NULL,
                        readingMode TEXT NOT NULL,
                        baseNovelUrl TEXT NOT NULL,
                        sourceName TEXT NOT NULL
                    )
                """.trimIndent())

                // Add indices
                db.execSQL("CREATE UNIQUE INDEX index_library_items_url ON library_items_new (url)")
                db.execSQL("CREATE INDEX index_library_items_baseTitle ON library_items_new (baseTitle)")
                db.execSQL("CREATE INDEX index_library_items_isCurrentlyReading ON library_items_new (isCurrentlyReading)")
                db.execSQL("CREATE INDEX index_library_items_lastRead ON library_items_new (lastRead)")

                // Copy data, keeping the one with latest lastRead for each URL
                // Ordering by lastRead ASC ensures the latest one "wins" with INSERT OR REPLACE
                db.execSQL("""
                    INSERT OR REPLACE INTO library_items_new (
                        id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                        lastReadOffset, lastReadOffsetFraction, hasUpdates, chapterSummaries,
                        baseTitle, readingMode, baseNovelUrl, sourceName
                    ) SELECT
                        id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                        lastReadOffset, lastReadOffsetFraction, hasUpdates, chapterSummaries,
                        baseTitle, readingMode, baseNovelUrl, sourceName
                    FROM library_items
                    ORDER BY lastRead ASC
                """.trimIndent())

                db.execSQL("DROP TABLE library_items")
                db.execSQL("ALTER TABLE library_items_new RENAME TO library_items")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE library_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        progress INTEGER NOT NULL,
                        isCurrentlyReading INTEGER NOT NULL,
                        currentChapter TEXT NOT NULL,
                        currentChapterUrl TEXT NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        contentType TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        lastRead INTEGER NOT NULL,
                        isDownloading INTEGER NOT NULL,
                        lastScrollPosition REAL NOT NULL,
                        lastReadIndex INTEGER NOT NULL,
                        lastReadOffset INTEGER NOT NULL,
                        lastReadOffsetFraction REAL,
                        hasUpdates INTEGER NOT NULL,
                        chapterSummaries TEXT NOT NULL,
                        baseTitle TEXT NOT NULL,
                        readingMode TEXT NOT NULL,
                        baseNovelUrl TEXT NOT NULL,
                        sourceName TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO library_items_new (
                        id, title, url, timestamp, progress, isCurrentlyReading,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition,
                        lastReadIndex, lastReadOffset, lastReadOffsetFraction, hasUpdates,
                        chapterSummaries, baseTitle, readingMode, baseNovelUrl, sourceName
                    ) SELECT
                        id, title, url, timestamp, progress, isCurrentlyReading,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition,
                        lastReadIndex, lastReadOffset, lastReadOffsetFraction, hasUpdates,
                        chapterSummaries, baseTitle, readingMode, baseNovelUrl, sourceName
                    FROM library_items
                """.trimIndent())

                db.execSQL("DROP TABLE library_items")
                db.execSQL("ALTER TABLE library_items_new RENAME TO library_items")

                db.execSQL("CREATE UNIQUE INDEX index_library_items_url ON library_items (url)")
                db.execSQL("CREATE INDEX index_library_items_baseTitle ON library_items (baseTitle)")
                db.execSQL("CREATE INDEX index_library_items_isCurrentlyReading ON library_items (isCurrentlyReading)")
                db.execSQL("CREATE INDEX index_library_items_lastRead ON library_items (lastRead)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_items ADD COLUMN isDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE library_items ADD COLUMN downloadedAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_image_state (
                        chapterUrl TEXT NOT NULL,
                        imageUrl TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastAttemptMs INTEGER NOT NULL DEFAULT 0,
                        httpStatusCode INTEGER,
                        PRIMARY KEY(chapterUrl, imageUrl)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_image_state_chapterUrl ON chapter_image_state (chapterUrl)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_image_state_status ON chapter_image_state (status)")
            }
        }

        /**
         * Schema unification for reading position:
         *  - Drop unused `lastReadOffset` (raw px — meaningless across reflow / item resize).
         *  - Add `lastReadElementKey` (stable per-element anchor; "" = unset).
         *  - Make `lastReadOffsetFraction` NOT NULL with sentinel -1.0 (= unknown), backfilling NULL rows.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE library_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        progress INTEGER NOT NULL,
                        isCurrentlyReading INTEGER NOT NULL,
                        currentChapter TEXT NOT NULL,
                        currentChapterUrl TEXT NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        contentType TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        lastRead INTEGER NOT NULL,
                        isDownloading INTEGER NOT NULL,
                        lastScrollPosition REAL NOT NULL,
                        lastReadIndex INTEGER NOT NULL,
                        lastReadElementKey TEXT NOT NULL DEFAULT '',
                        lastReadOffsetFraction REAL NOT NULL DEFAULT -1,
                        hasUpdates INTEGER NOT NULL,
                        chapterSummaries TEXT NOT NULL,
                        baseTitle TEXT NOT NULL,
                        readingMode TEXT NOT NULL,
                        baseNovelUrl TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        isDownloaded INTEGER NOT NULL DEFAULT 0,
                        downloadedAt INTEGER
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO library_items_new (
                        id, title, url, timestamp, progress, isCurrentlyReading,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition,
                        lastReadIndex, lastReadElementKey, lastReadOffsetFraction, hasUpdates,
                        chapterSummaries, baseTitle, readingMode, baseNovelUrl, sourceName,
                        isDownloaded, downloadedAt
                    ) SELECT
                        id, title, url, timestamp, progress, isCurrentlyReading,
                        currentChapter, currentChapterUrl, totalChapters, contentType,
                        dateAdded, lastRead, isDownloading, lastScrollPosition,
                        lastReadIndex, '', COALESCE(lastReadOffsetFraction, -1.0), hasUpdates,
                        chapterSummaries, baseTitle, readingMode, baseNovelUrl, sourceName,
                        isDownloaded, downloadedAt
                    FROM library_items
                """.trimIndent())

                db.execSQL("DROP TABLE library_items")
                db.execSQL("ALTER TABLE library_items_new RENAME TO library_items")

                db.execSQL("CREATE UNIQUE INDEX index_library_items_url ON library_items (url)")
                db.execSQL("CREATE INDEX index_library_items_baseTitle ON library_items (baseTitle)")
                db.execSQL("CREATE INDEX index_library_items_isCurrentlyReading ON library_items (isCurrentlyReading)")
                db.execSQL("CREATE INDEX index_library_items_lastRead ON library_items (lastRead)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS image_dimension_cache (
                        imageUrl TEXT NOT NULL PRIMARY KEY,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        cachedAtMs INTEGER NOT NULL,
                        parserVersion INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(DB_VERSION_9, DB_VERSION_10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_items ADD COLUMN coverImageUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(DB_VERSION_10, DB_VERSION_11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `reading_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `novelKey` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL,
                        `activeMillis` INTEGER NOT NULL,
                        `chaptersCompleted` INTEGER NOT NULL,
                        `seeded` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_reading_sessions_novelKey` ON `reading_sessions` (`novelKey`)
                """.trimIndent())
            }
        }
    }
}

