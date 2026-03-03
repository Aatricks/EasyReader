package io.aatricks.novelscraper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.aatricks.novelscraper.data.model.LibraryItem

@Database(entities = [LibraryItem::class], version = 2, exportSchema = false)
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
    }
}
