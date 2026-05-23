package io.aatricks.easyreader.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.data.repository.content.DefaultPdfDocumentOpener
import io.aatricks.easyreader.data.repository.content.PdfDocumentOpener
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HtmlCacheDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaCacheDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubCacheDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HtmlDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpubDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebOfflineDownloadsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChapterListCacheDir

@Module
@InstallIn(SingletonComponent::class)
object ContentModule {

    @Provides
    @Singleton
    @HtmlCacheDir
    fun provideCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "html_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @MediaCacheDir
    fun provideMediaCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "media_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @EpubCacheDir
    fun provideEpubCacheDir(@ApplicationContext context: Context): File {
        return File(context.cacheDir, "epub_cache").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @HtmlDownloadsDir
    fun provideHtmlDownloadsDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "downloads/html").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @MediaDownloadsDir
    fun provideMediaDownloadsDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "downloads/media").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @EpubDownloadsDir
    fun provideEpubDownloadsDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "downloads/epub").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @WebOfflineDownloadsDir
    fun provideWebOfflineDownloadsDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "downloads/web_chapters_v2").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    @ChapterListCacheDir
    fun provideChapterListCacheDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "chapter_lists").apply { if (!exists()) mkdirs() }
    }

    @Provides
    @Singleton
    internal fun providePdfDocumentOpener(@ApplicationContext context: Context): PdfDocumentOpener {
        return DefaultPdfDocumentOpener(context)
    }
}
