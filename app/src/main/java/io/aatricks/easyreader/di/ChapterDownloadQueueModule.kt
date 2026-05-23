package io.aatricks.easyreader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.work.ChapterDownloadQueue
import io.aatricks.easyreader.work.WorkManagerChapterDownloadQueue
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChapterDownloadQueueModule {
    @Binds
    @Singleton
    abstract fun bindChapterDownloadQueue(impl: WorkManagerChapterDownloadQueue): ChapterDownloadQueue
}
