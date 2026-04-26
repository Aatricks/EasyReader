package io.aatricks.easyreader.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.aatricks.easyreader.data.model.ContentType
import io.aatricks.easyreader.data.model.ReadingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdDatabases = mutableSetOf<String>()

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        createdDatabases.forEach(context::deleteDatabase)
        createdDatabases.clear()
    }

    @Test
    fun migrate1ToCurrent_preservesLibraryData() = runBlocking {
        val dbName = migrationDbName("1-to-current")
        createVersion1Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(dbName, CURRENT_VERSION, true, *ALL_MIGRATIONS)
        assertMigratedCurrentData(dbName, expectsLastReadOffsetFraction = false)
    }

    @Test
    fun migrate2ToCurrent_preservesLibraryData() = runBlocking {
        val dbName = migrationDbName("2-to-current")
        createVersion2Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(dbName, CURRENT_VERSION, true, *ALL_MIGRATIONS)
        assertMigratedCurrentData(dbName, expectsLastReadOffsetFraction = false)
    }

    @Test
    fun migrate3ToCurrent_preservesLibraryData() = runBlocking {
        val dbName = migrationDbName("3-to-current")
        createVersion3Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(dbName, CURRENT_VERSION, true, *ALL_MIGRATIONS)
        assertMigratedCurrentData(dbName, expectsLastReadOffsetFraction = true)
    }

    @Test
    fun migrate4ToCurrent_preservesLibraryDataAndDropsSelectionSafely() = runBlocking {
        val dbName = migrationDbName("4-to-current")
        createVersion4Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(dbName, CURRENT_VERSION, true, *ALL_MIGRATIONS)
        assertMigratedCurrentData(dbName, expectsLastReadOffsetFraction = true)
    }

    @Test
    fun migrate1To2() {
        val dbName = migrationDbName("1-to-2")
        createVersion1Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            AppDatabase.MIGRATION_1_2
        ).use { database ->
            assertFalse(hasColumn(database, "type"))
            assertTrue(hasColumn(database, "isSelected"))
            assertEquals(2, rowCount(database))
        }
    }

    @Test
    fun migrate2To3() {
        val dbName = migrationDbName("2-to-3")
        createVersion2Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            AppDatabase.MIGRATION_2_3
        ).use { database ->
            assertTrue(hasColumn(database, "lastReadOffsetFraction"))
            database.query(SimpleSQLiteQuery("SELECT lastReadOffsetFraction FROM library_items WHERE id = 'item-legacy-nullable'"))
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                }
        }
    }

    @Test
    fun migrate3To4() {
        val dbName = migrationDbName("3-to-4")
        createVersion3Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            AppDatabase.MIGRATION_3_4
        ).use { database ->
            assertIndexExists(database, "index_library_items_url")
            assertIndexExists(database, "index_library_items_baseTitle")
            assertIndexExists(database, "index_library_items_isCurrentlyReading")
            assertIndexExists(database, "index_library_items_lastRead")
            assertEquals(2, rowCount(database))
        }
    }

    @Test
    fun migrate4To5() {
        val dbName = migrationDbName("4-to-5")
        createVersion4Database(dbName)

        migrationTestHelper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AppDatabase.MIGRATION_4_5
        ).use { database ->
            assertFalse(hasColumn(database, "isSelected"))
            assertIndexExists(database, "index_library_items_url")
            assertIndexExists(database, "index_library_items_baseTitle")
            assertIndexExists(database, "index_library_items_isCurrentlyReading")
            assertIndexExists(database, "index_library_items_lastRead")
            assertEquals(2, rowCount(database))
        }
    }

    private fun createVersion1Database(dbName: String) {
        createDatabaseAtVersion(
            dbName = dbName,
            version = 1,
            createTableSql = """
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
                    hasUpdates INTEGER NOT NULL,
                    chapterSummaries TEXT NOT NULL,
                    baseTitle TEXT NOT NULL,
                    readingMode TEXT NOT NULL,
                    baseNovelUrl TEXT NOT NULL,
                    sourceName TEXT NOT NULL,
                    type TEXT NOT NULL
                )
            """.trimIndent(),
            insertSqls = listOf(
                version1StandardItemInsertSql(),
                version1LegacyNullableItemInsertSql()
            )
        )
    }

    private fun createVersion2Database(dbName: String) {
        createDatabaseAtVersion(
            dbName = dbName,
            version = 2,
            createTableSql = """
                CREATE TABLE library_items (
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
            """.trimIndent(),
            insertSqls = listOf(
                standardItemInsertSql(includeLastReadOffsetFraction = false),
                legacyNullableItemInsertSql(includeLastReadOffsetFraction = false)
            )
        )
    }

    private fun createVersion3Database(dbName: String) {
        createDatabaseAtVersion(
            dbName = dbName,
            version = 3,
            createTableSql = """
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
            """.trimIndent(),
            insertSqls = listOf(
                standardItemInsertSql(includeLastReadOffsetFraction = true),
                legacyNullableItemInsertSql(includeLastReadOffsetFraction = true)
            )
        )
    }

    private fun createVersion4Database(dbName: String) {
        createDatabaseAtVersion(
            dbName = dbName,
            version = 4,
            createTableSql = """
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
            """.trimIndent(),
            indexSqls = CURRENT_INDEX_SQL,
            insertSqls = listOf(
                standardItemInsertSql(includeLastReadOffsetFraction = true),
                legacyNullableItemInsertSql(includeLastReadOffsetFraction = true)
            )
        )
    }

    private fun createDatabaseAtVersion(
        dbName: String,
        version: Int,
        createTableSql: String,
        indexSqls: List<String> = emptyList(),
        insertSqls: List<String>
    ) {
        createdDatabases += dbName
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createTableSql)
                    indexSqls.forEach(db::execSQL)
                    insertSqls.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    private suspend fun assertMigratedCurrentData(dbName: String, expectsLastReadOffsetFraction: Boolean) {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val primary = database.libraryDao().getItemById("item-primary")
            assertNotNull(primary)
            assertEquals("My Novel", primary?.title)
            assertEquals("https://example.com/novel", primary?.url)
            assertEquals(75, primary?.progress)
            assertEquals(true, primary?.isCurrentlyReading)
            assertEquals("Chapter 12", primary?.currentChapter)
            assertEquals("https://example.com/novel/chapter-12", primary?.currentChapterUrl)
            assertEquals(12, primary?.totalChapters)
            assertEquals(ContentType.WEB, primary?.contentType)
            assertEquals(12345L, primary?.dateAdded)
            assertEquals(23456L, primary?.lastRead)
            assertEquals(1, primary?.lastReadIndex)
            assertEquals(9, primary?.lastReadOffset)
            if (expectsLastReadOffsetFraction) {
                assertEquals(0.25f, primary?.lastReadOffsetFraction)
            } else {
                assertNull(primary?.lastReadOffsetFraction)
            }
            assertEquals(true, primary?.hasUpdates)
            assertEquals("Base Title", primary?.baseTitle)
            assertEquals(ReadingMode.PAGED, primary?.readingMode)
            assertEquals("https://example.com/base", primary?.baseNovelUrl)
            assertEquals("SourceName", primary?.sourceName)

            val nullable = database.libraryDao().getItemById("item-legacy-nullable")
            assertNotNull(nullable)
            assertEquals("https://example.com/novel-nullable", nullable?.url)
            assertEquals("https://example.com/novel-nullable/chapter-1", nullable?.currentChapterUrl)
            assertEquals(ReadingMode.VERTICAL, nullable?.readingMode)
            assertNull(nullable?.lastReadOffsetFraction)

            assertFalse(hasColumn(database.openHelper.readableDatabase, "isSelected"))
        } finally {
            database.close()
        }
    }

    private fun hasColumn(database: SupportSQLiteDatabase, columnName: String): Boolean {
        database.query(SimpleSQLiteQuery("PRAGMA table_info(library_items)")).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }

        return false
    }

    private fun rowCount(database: SupportSQLiteDatabase): Int {
        database.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM library_items")).use { cursor ->
            check(cursor.moveToFirst()) { "Expected count cursor row." }
            return cursor.getInt(0)
        }
    }

    private fun assertIndexExists(database: SupportSQLiteDatabase, indexName: String) {
        database.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf<Any>(indexName)))
            .use { cursor ->
                assertTrue("Expected index `$indexName` to exist", cursor.moveToFirst())
            }
    }

    private fun migrationDbName(suffix: String): String = "migration-$suffix.db"

    private fun standardItemInsertSql(includeLastReadOffsetFraction: Boolean): String {
        return if (includeLastReadOffsetFraction) {
            """
                INSERT INTO library_items (
                    id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                    currentChapter, currentChapterUrl, totalChapters, contentType,
                    dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                    lastReadOffset, lastReadOffsetFraction, hasUpdates, chapterSummaries,
                    baseTitle, readingMode, baseNovelUrl, sourceName
                ) VALUES (
                    'item-primary', 'My Novel', 'https://example.com/novel', 111, 75, 1, 1,
                    'Chapter 12', 'https://example.com/novel/chapter-12', 12, 'WEB',
                    12345, 23456, 0, 0.75, 1, 9, 0.25, 1,
                    '{}', 'Base Title', 'PAGED', 'https://example.com/base', 'SourceName'
                )
            """.trimIndent()
        } else {
            """
                INSERT INTO library_items (
                    id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                    currentChapter, currentChapterUrl, totalChapters, contentType,
                    dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                    lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                    baseNovelUrl, sourceName
                ) VALUES (
                    'item-primary', 'My Novel', 'https://example.com/novel', 111, 75, 1, 1,
                    'Chapter 12', 'https://example.com/novel/chapter-12', 12, 'WEB',
                    12345, 23456, 0, 0.75, 1, 9, 1,
                    '{}', 'Base Title', 'PAGED', 'https://example.com/base', 'SourceName'
                )
            """.trimIndent()
        }
    }

    private fun legacyNullableItemInsertSql(includeLastReadOffsetFraction: Boolean): String {
        return if (includeLastReadOffsetFraction) {
            """
                INSERT INTO library_items (
                    id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                    currentChapter, currentChapterUrl, totalChapters, contentType,
                    dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                    lastReadOffset, lastReadOffsetFraction, hasUpdates, chapterSummaries,
                    baseTitle, readingMode, baseNovelUrl, sourceName
                ) VALUES (
                    'item-legacy-nullable', 'Nullable Legacy', 'https://example.com/novel-nullable', 222, 5, 0, 0,
                    'Chapter 1', 'https://example.com/novel-nullable/chapter-1', 30, 'WEB',
                    33333, 44444, 0, 0.0, 0, 0, NULL, 0,
                    '{}', 'Legacy Base', 'VERTICAL', 'https://example.com/legacy', 'LegacySource'
                )
            """.trimIndent()
        } else {
            """
                INSERT INTO library_items (
                    id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                    currentChapter, currentChapterUrl, totalChapters, contentType,
                    dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                    lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                    baseNovelUrl, sourceName
                ) VALUES (
                    'item-legacy-nullable', 'Nullable Legacy', 'https://example.com/novel-nullable', 222, 5, 0, 0,
                    'Chapter 1', 'https://example.com/novel-nullable/chapter-1', 30, 'WEB',
                    33333, 44444, 0, 0.0, 0, 0, 0,
                    '{}', 'Legacy Base', 'VERTICAL', 'https://example.com/legacy', 'LegacySource'
                )
            """.trimIndent()
        }
    }

    private fun version1StandardItemInsertSql(): String {
        return """
            INSERT INTO library_items (
                id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                currentChapter, currentChapterUrl, totalChapters, contentType,
                dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                baseNovelUrl, sourceName, type
            ) VALUES (
                'item-primary', 'My Novel', 'https://example.com/novel', 111, 75, 1, 1,
                'Chapter 12', 'https://example.com/novel/chapter-12', 12, 'WEB',
                12345, 23456, 0, 0.75, 1, 9, 1,
                '{}', 'Base Title', 'PAGED', 'https://example.com/base', 'SourceName', 'novel'
            )
        """.trimIndent()
    }

    private fun version1LegacyNullableItemInsertSql(): String {
        return """
            INSERT INTO library_items (
                id, title, url, timestamp, progress, isCurrentlyReading, isSelected,
                currentChapter, currentChapterUrl, totalChapters, contentType,
                dateAdded, lastRead, isDownloading, lastScrollPosition, lastReadIndex,
                lastReadOffset, hasUpdates, chapterSummaries, baseTitle, readingMode,
                baseNovelUrl, sourceName, type
            ) VALUES (
                'item-legacy-nullable', 'Nullable Legacy', 'https://example.com/novel-nullable', 222, 5, 0, 0,
                'Chapter 1', 'https://example.com/novel-nullable/chapter-1', 30, 'WEB',
                33333, 44444, 0, 0.0, 0, 0, 0,
                '{}', 'Legacy Base', 'VERTICAL', 'https://example.com/legacy', 'LegacySource', 'novel'
            )
        """.trimIndent()
    }

    companion object {
        private const val CURRENT_VERSION = 5
        private val ALL_MIGRATIONS = arrayOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5
        )
        private val CURRENT_INDEX_SQL = listOf(
            "CREATE UNIQUE INDEX index_library_items_url ON library_items (url)",
            "CREATE INDEX index_library_items_baseTitle ON library_items (baseTitle)",
            "CREATE INDEX index_library_items_isCurrentlyReading ON library_items (isCurrentlyReading)",
            "CREATE INDEX index_library_items_lastRead ON library_items (lastRead)"
        )
    }
}
