package io.aatricks.novelscraper.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.aatricks.novelscraper.data.local.AppDatabase
import io.aatricks.novelscraper.data.local.LibraryDao
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
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(false)
        .build()
    }

    @Provides
    @Singleton
    fun provideLibraryDao(database: AppDatabase): LibraryDao {
        return database.libraryDao()
    }
}