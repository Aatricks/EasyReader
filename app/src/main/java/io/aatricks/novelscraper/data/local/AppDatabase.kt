package io.aatricks.novelscraper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.aatricks.novelscraper.data.model.LibraryItem

@Database(entities = [LibraryItem::class], version = 5, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
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
    }
}
