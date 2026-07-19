package io.aatricks.easyreader.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.data.local.AppDatabase
import io.aatricks.easyreader.data.local.ChapterImageStateDao
import io.aatricks.easyreader.data.local.ImageDimensionDao
import io.aatricks.easyreader.data.local.LibraryDao
import io.aatricks.easyreader.data.local.ReadingSessionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "easy_reader_v2.db"
        )
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11
        )
        .build()
    }

    @Provides
    @Singleton
    fun provideLibraryDao(database: AppDatabase): LibraryDao {
        return database.libraryDao()
    }

    @Provides
    @Singleton
    fun provideChapterImageStateDao(database: AppDatabase): ChapterImageStateDao {
        return database.chapterImageStateDao()
    }

    @Provides
    @Singleton
    fun provideImageDimensionDao(database: AppDatabase): ImageDimensionDao {
        return database.imageDimensionDao()
    }

    @Provides
    @Singleton
    fun provideReadingSessionDao(database: AppDatabase): ReadingSessionDao {
        return database.readingSessionDao()
    }
}

