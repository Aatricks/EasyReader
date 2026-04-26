package io.aatricks.novelscraper.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.aatricks.novelscraper.data.model.ContentType
import io.aatricks.novelscraper.data.model.ReadingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-4-to-5.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration4to5_preservesDurableFields_andDropsSelectionColumn() = runBlocking {
        createVersion4Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val item = database.libraryDao().getItemById("item-1")
        assertNotNull(item)
        assertEquals("My Novel", item?.title)
        assertEquals("https://example.com/novel", item?.url)
        assertEquals(75, item?.progress)
        assertEquals(true, item?.isCurrentlyReading)
        assertEquals("Chapter 12", item?.currentChapter)
        assertEquals("https://example.com/novel/chapter-12", item?.currentChapterUrl)
        assertEquals(12, item?.totalChapters)
        assertEquals(ContentType.WEB, item?.contentType)
        assertEquals(12345L, item?.dateAdded)
        assertEquals(23456L, item?.lastRead)
        assertEquals(1, item?.lastReadIndex)
        assertEquals(9, item?.lastReadOffset)
        assertEquals(0.25f, item?.lastReadOffsetFraction)
        assertEquals(true, item?.hasUpdates)
        assertEquals("Base Title", item?.baseTitle)
        assertEquals(ReadingMode.PAGED, item?.readingMode)
        assertEquals("https://example.com/base", item?.baseNovelUrl)
        assertEquals("SourceName", item?.sourceName)
        assertFalse(hasColumn(database, "isSelected"))

        database.close()
    }

    private fun createVersion4Database() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE library_items (
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
                    db.execSQL("CREATE UNIQUE INDEX index_library_items_url ON library_items (url)")
                    db.execSQL("CREATE INDEX index_library_items_baseTitle ON library_items (baseTitle)")
                    db.execSQL("CREATE INDEX index_library_items_isCurrentlyReading ON library_items (isCurrentlyReading)")
                    db.execSQL("CREATE INDEX index_library_items_lastRead ON library_items (lastRead)")

                    db.execSQL("""
                        INSERT INTO library_items (
                            id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                            currentChapter, currentChapterUrl, totalChapters, contentType,
                            dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                            lastReadOffset, lastReadOffsetFraction, hasUpdates, chapterSummaries,
                            baseTitle, readingMode, baseNovelUrl, sourceName
                        ) VALUES (
                            'item-1', 'My Novel', 'https://example.com/novel', 111, 75, 1, 1,
                            'Chapter 12', 'https://example.com/novel/chapter-12', 12, 'WEB',
                            12345, 23456, 0, 0.75, 1, 9, 0.25, 1,
                            '{}', 'Base Title', 'PAGED', 'https://example.com/base', 'SourceName'
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    private fun hasColumn(database: AppDatabase, columnName: String): Boolean {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery("PRAGMA table_info(library_items)")).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }

        return false
    }
}
